package org.jahia.modules.external.events.rest;

import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.internal.inject.ConfiguredValidator;
import org.glassfish.jersey.server.model.Invocable;
import org.jahia.services.content.JCRSessionFactory;

import javax.inject.Singleton;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.ValidationException;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import javax.validation.metadata.BeanDescriptor;
import javax.validation.metadata.MethodDescriptor;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Simpler version of ValidationFeature
 */
public class SimpleValidationFeature implements Feature {

    @Override
    public boolean configure(final FeatureContext context) {
        context.register(new AbstractBinder() {
            @Override
            protected void configure() {
                bindFactory(ConfiguredValidatorFactory.class, Singleton.class).to(ConfiguredValidator.class).in(PerLookup.class);
            }
        });
        context.register(ValidationExceptionMapper.class);

        return true;
    }

    public static class ConfiguredValidatorFactory implements Factory<ConfiguredValidator> {
        @Override
        public ConfiguredValidator provide() {
            return new ConfiguredValidatorImpl(JCRSessionFactory.getInstance().getValidatorFactoryBean().getValidator());
        }

        @Override
        public void dispose(ConfiguredValidator configuredValidator) {

        }
    }

    public static class ConfiguredValidatorImpl implements ConfiguredValidator {
        private final Validator delegate;

        public ConfiguredValidatorImpl(Validator delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
            return delegate.validate(object, groups);
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
            return delegate.validateProperty(object, propertyName, groups);
        }

        @Override
        public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
            return delegate.validateValue(beanType, propertyName, value, groups);
        }

        @Override
        public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
            return delegate.getConstraintsForClass(clazz);
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            return delegate.unwrap(type);
        }

        @Override
        public ExecutableValidator forExecutables() {
            return delegate.forExecutables();
        }

        @Override
        public void validateResourceAndInputParams(Object resource, Invocable resourceMethod, Object[] args) throws ConstraintViolationException {
            final Set<ConstraintViolation<Object>> constraintViolations = new HashSet<ConstraintViolation<Object>>();
            final BeanDescriptor beanDescriptor = getConstraintsForClass(resource.getClass());

            // Resource validation.
            if (beanDescriptor.isBeanConstrained()) {
                constraintViolations.addAll(validate(resource));
            }

            if (resourceMethod != null) {
                final Method handlingMethod = resourceMethod.getHandlingMethod();

                // Resource method validation - input parameters.
                final MethodDescriptor methodDescriptor = beanDescriptor.getConstraintsForMethod(handlingMethod.getName(),
                        handlingMethod.getParameterTypes());

                if (methodDescriptor != null
                        && methodDescriptor.hasConstrainedParameters()) {
                    constraintViolations.addAll(forExecutables().validateParameters(resource, handlingMethod, args));
                }
            }

            if (!constraintViolations.isEmpty()) {
                throw new ConstraintViolationException(constraintViolations);
            }

        }

        @Override
        public void validateResult(Object resource, Invocable resourceMethod, Object result) throws ConstraintViolationException {

        }
    }

    public static class ValidationExceptionMapper  implements ExceptionMapper<ValidationException> {
        @Override
        public Response toResponse(ValidationException exception) {
            if (exception instanceof ConstraintViolationException) {
                ConstraintViolationException cve = (ConstraintViolationException)exception;
                Response.ResponseBuilder response = Response.status(Response.Status.BAD_REQUEST);
                response.type(MediaType.TEXT_PLAIN_TYPE);
                StringBuilder builder = new StringBuilder();

                for (ConstraintViolation<?> violation : cve.getConstraintViolations()) {
                    builder.append(violation.getMessage());
                    builder.append(' ');
                    builder.append('(');
                    builder.append("path = " );
                    builder.append(violation.getPropertyPath());
                    builder.append(',');
                    builder.append(' ');
                    builder.append("invalidValue = ");
                    builder.append(violation.getInvalidValue());
                    builder.append(')');
                    builder.append('\n');
                }
                response.entity(builder.toString());

                return response.build();
            } else {
                return Response.serverError().entity(exception.getMessage()).build();
            }
        }
    }
}