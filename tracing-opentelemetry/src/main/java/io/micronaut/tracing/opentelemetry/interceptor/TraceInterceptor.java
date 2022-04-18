/*
 * Copyright 2017-2022 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.tracing.opentelemetry.interceptor;

import io.micronaut.aop.InterceptPhase;
import io.micronaut.aop.InterceptedMethod;
import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.tracing.annotation.ContinueSpan;
import io.micronaut.tracing.annotation.NewSpan;
import io.micronaut.tracing.annotation.SpanTag;
import io.micronaut.tracing.opentelemetry.instrument.http.MicronautCodeTelemetryBuilder;
import io.micronaut.tracing.opentelemetry.instrument.util.TracingPublisher;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.extension.annotations.WithSpan;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.util.ClassAndMethod;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;

import java.util.concurrent.CompletionStage;


/**
 * Implements tracing logic for <code>ContinueSpan</code> and <code>NewSpan</code>
 * using the Open Tracing API.
 *
 * @author Nemanja Mikic
 */
@Singleton
@Requires(beans = Tracer.class)
@InterceptorBean({ContinueSpan.class, NewSpan.class, WithSpan.class})
public class TraceInterceptor implements MethodInterceptor<Object, Object> {

    private final Instrumenter<ClassAndMethod, Object> instrumenter;

    /**
     * Initialize the interceptor with tracer and conversion service.
     *
     * @param openTelemetry the openTelemetry
     */
    public TraceInterceptor(OpenTelemetry openTelemetry) {
        instrumenter = new MicronautCodeTelemetryBuilder(openTelemetry).build();
    }

    @Override
    public int getOrder() {
        return InterceptPhase.TRACE.getPosition();
    }

    /**
     * Logs an error to the span.
     *
     * @param context the span
     * @param e       the error
     */
    public static void logError(Context context, Throwable e) {
        Span.fromContext(context).recordException(e);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        boolean isContinue = context.hasAnnotation(ContinueSpan.class);
        AnnotationValue<NewSpan> newSpan = context.getAnnotation(NewSpan.class);
        boolean isNew = newSpan != null;
        if (!isContinue && !isNew) {
            return context.proceed();
        }
        Context currentContext = Context.current();

        ClassAndMethod classAndMethod = ClassAndMethod.create(context.getDeclaringType(), context.getMethodName());

        if (isContinue) {
            InterceptedMethod interceptedMethod = InterceptedMethod.of(context);
            try {
                switch (interceptedMethod.resultType()) {
                    case PUBLISHER:
                        Publisher<?> publisher = interceptedMethod.interceptResultAsPublisher();
                        if (publisher instanceof TracingPublisher) {
                            return publisher;
                        }
                        return interceptedMethod.handleResult(
                            new TracingPublisher(
                                publisher, null, classAndMethod,
                                currentContext
                            ) {

                                @Override
                                public void doOnSubscribe(@NonNull Context openTelemetryContext) {
                                    tagArguments(context, openTelemetryContext);
                                }

                            });
                    case COMPLETION_STAGE:
                    case SYNCHRONOUS:
                        tagArguments(context, currentContext);
                        try {
                            return context.proceed();
                        } catch (RuntimeException e) {
                            logError(currentContext, e);
                            throw e;
                        }
                    default:
                        return interceptedMethod.unsupported();
                }
            } catch (Exception e) {
                return interceptedMethod.handleException(e);
            }
        } else {
            // must be new
            // don't create a nested span if you're not supposed to.
            String operationName = newSpan.stringValue().orElse(null);

            if (operationName != null) {
                classAndMethod = ClassAndMethod.create(classAndMethod.declaringClass(), classAndMethod.methodName() + "#" + operationName);
            }

            InterceptedMethod interceptedMethod = InterceptedMethod.of(context);
            try {
                switch (interceptedMethod.resultType()) {
                    case PUBLISHER:
                        Publisher<?> publisher = interceptedMethod.interceptResultAsPublisher();
                        if (publisher instanceof TracingPublisher) {
                            return publisher;
                        }
                        return interceptedMethod.handleResult(new TracingPublisher(
                            publisher, instrumenter, classAndMethod,
                            currentContext
                        ) {

                            @Override
                            public void doOnSubscribe(@NonNull Context openTelemetryContext) {
                                tagArguments(context, openTelemetryContext);
                            }

                        });
                    case COMPLETION_STAGE:
                        if (!instrumenter.shouldStart(currentContext, classAndMethod)) {
                            return context.proceed();
                        }
                        Context newContext = instrumenter.start(currentContext, classAndMethod);
                        try (Scope ignored = newContext.makeCurrent()) {
                            tagArguments(context, newContext);
                            try {
                                CompletionStage<?> completionStage = interceptedMethod.interceptResultAsCompletionStage();
                                if (completionStage != null) {
                                    completionStage = completionStage.whenComplete((o, throwable) -> {
                                        if (throwable != null) {
                                            logError(newContext, throwable);
                                        }
                                    });
                                }
                                return interceptedMethod.handleResult(completionStage);
                            } catch (RuntimeException e) {
                                logError(newContext, e);
                                throw e;
                            }
                        }
                    case SYNCHRONOUS:
                        if (!instrumenter.shouldStart(currentContext, classAndMethod)) {
                            return context.proceed();
                        }
                        newContext = instrumenter.start(currentContext, classAndMethod);
                        try (Scope scope = newContext.makeCurrent()) {
                            tagArguments(context, newContext);
                            try {
                                return context.proceed();
                            } catch (RuntimeException e) {
                                logError(newContext, e);
                                throw e;
                            }
                        }
                    default:
                        return interceptedMethod.unsupported();
                }
            } catch (Exception e) {
                return interceptedMethod.handleException(e);
            }
        }
    }

    private void tagArguments(MethodInvocationContext<Object, Object> context, Context openTelemetryContext) {
        Argument<?>[] arguments = context.getArguments();
        Object[] parameterValues = context.getParameterValues();
        for (int i = 0; i < arguments.length; i++) {
            Argument<?> argument = arguments[i];
            AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
            if (annotationMetadata.hasAnnotation(SpanTag.class)) {
                Object v = parameterValues[i];
                if (v != null) {
                    String tagName = annotationMetadata.stringValue(SpanTag.class).orElse(argument.getName());
                    Span span = Span.current();
                    span.setAttribute(tagName, v.toString());
                }
            }
        }
    }
}