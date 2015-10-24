/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tomitribe.connector.starter.adapter;

import org.tomitribe.connector.starter.api.Execute;
import org.tomitribe.connector.starter.authenticator.AuthenticateWork;
import org.tomitribe.connector.starter.authenticator.WorkSecurityContext;

import javax.resource.ResourceException;
import javax.resource.spi.ActivationSpec;
import javax.resource.spi.BootstrapContext;
import javax.resource.spi.ConfigProperty;
import javax.resource.spi.Connector;
import javax.resource.spi.ResourceAdapter;
import javax.resource.spi.ResourceAdapterInternalException;
import javax.resource.spi.endpoint.MessageEndpoint;
import javax.resource.spi.endpoint.MessageEndpointFactory;
import javax.resource.spi.work.Work;
import javax.resource.spi.work.WorkException;
import javax.resource.spi.work.WorkManager;
import javax.transaction.xa.XAResource;
import javax.validation.constraints.NotNull;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Connector(description = "Sample Resource Adapter", displayName = "Sample Resource Adapter", eisType = "Sample Resource Adapter", version = "1.0")
public class SampleResourceAdapter implements ResourceAdapter, SecurityHandler {

    @ConfigProperty(defaultValue = "15")
    @NotNull
    private Integer interval;

    @ConfigProperty
    @NotNull
    private String username;

    @ConfigProperty
    @NotNull
    private String password;

    public void start(BootstrapContext bootstrapContext) throws ResourceAdapterInternalException {
        workManager = bootstrapContext.getWorkManager();
    }

    public void stop() {
    }

    public void endpointActivation(final MessageEndpointFactory messageEndpointFactory, final ActivationSpec activationSpec)
            throws ResourceException
    {
        if (this.targets.size() == 0) {
            runWithSecurityContext(new Worker(this), username, password);
        }

        final SampleActivationSpec sampleActivationSpec = (SampleActivationSpec) activationSpec;

        workManager.scheduleWork(new Work() {

            @Override
            public void run() {
                try {
                    final MessageEndpoint messageEndpoint = messageEndpointFactory.createEndpoint(null);

                    final Class<?> endpointClass = sampleActivationSpec.getBeanClass() != null ? sampleActivationSpec
                            .getBeanClass() : messageEndpointFactory.getEndpointClass();

                    final List<Method> methodList = new ArrayList<>();
                    final Method[] methods = endpointClass.getMethods();
                    for (final Method method : methods) {
                        if (! Modifier.isPublic(method.getModifiers())) {
                            continue;
                        }

                        if (method.getAnnotation(Execute.class) != null) {
                            methodList.add(method);
                        }
                    }

                    final EndpointTarget target = new EndpointTarget(messageEndpoint, methodList.toArray(new Method[methodList.size()]));
                    targets.put(sampleActivationSpec, target);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void release() {
            }
        });
    }

    public void endpointDeactivation(MessageEndpointFactory messageEndpointFactory, ActivationSpec activationSpec) {
        final SampleActivationSpec sampleActivationSpec = (SampleActivationSpec) activationSpec;

        final EndpointTarget endpointTarget = targets.get(sampleActivationSpec);
        if (endpointTarget == null) {
            throw new IllegalStateException("No EndpointTarget to undeploy for ActivationSpec " + activationSpec);
        }

        endpointTarget.messageEndpoint.release();
    }

    public XAResource[] getXAResources(ActivationSpec[] activationSpecs) throws ResourceException {
        return new XAResource[0];
    }

    final Map<SampleActivationSpec, EndpointTarget> targets = new ConcurrentHashMap<SampleActivationSpec, EndpointTarget>();
    private WorkManager workManager;

    @Override
    public void runWithSecurityContext(Runnable runnable, String username, String password) {

        // create a work with a security context
        RunnableWork runnableWork = new RunnableWork(runnable);
        runnableWork.getWorkContexts().add(new WorkSecurityContext(username, password));

        // get the work manager to execute asynchronously
        try {
            workManager.startWork(runnableWork);
        } catch (WorkException e) {
            e.printStackTrace();
        }
    }

    public static class EndpointTarget {
        private final MessageEndpoint messageEndpoint;
        private final Method[] methods;

        public EndpointTarget(final MessageEndpoint messageEndpoint, final Method[] methods) {
            this.messageEndpoint = messageEndpoint;
            this.methods = methods;
        }

        public void invoke() throws InvocationTargetException, IllegalAccessException {
            for (final Method method : methods) {
                try {
                    try {
                        messageEndpoint.beforeDelivery(method);
                        method.invoke(messageEndpoint);
                    } finally {
                        messageEndpoint.afterDelivery();
                    }
                } catch (NoSuchMethodException | ResourceException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public boolean authenticate(String username, String password) {
        boolean authenticated = false;
        
        final AuthenticateWork authenticateWork = new AuthenticateWork(username, password);
        try {
            workManager.doWork(authenticateWork);
            authenticated = authenticateWork.isAuthenticated();
        } catch (WorkException e) {
            authenticated = false;
        }

        return authenticated;
    }

    public Integer getInterval() {
        return interval;
    }

    public void setInterval(Integer interval) {
        this.interval = interval;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
