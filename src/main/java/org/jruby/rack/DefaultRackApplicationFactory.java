/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
 * This source code is available under the MIT license.
 * See the file LICENSE.txt for details.
 */

package org.jruby.rack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import org.jruby.Ruby;
import org.jruby.RubyInstanceConfig;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ClassCache;

/**
 *
 * @author nicksieger
 */
public class DefaultRackApplicationFactory implements RackApplicationFactory {
    private String rackupScript;
    private RackContext rackContext;
    private ClassCache classCache;
    private RackApplication errorApplication;

    public void init(RackContext rackContext) {
        this.rackContext = rackContext;
        this.rackupScript = rackContext.getInitParameter("rackup");
        this.classCache = JavaEmbedUtils.createClassCache(
                Thread.currentThread().getContextClassLoader());
        if (errorApplication == null) {
            errorApplication = newErrorApplication();
        }
    }

    public RackApplication newApplication() throws RackInitializationException {
        return createApplication(new ApplicationObjectFactory() {
            public IRubyObject create(Ruby runtime) {
                return createApplicationObject(runtime);
            }
        });
    }

    public RackApplication getApplication() throws RackInitializationException {
        RackApplication app = newApplication();
        app.init();
        return app;
    }

    public void finishedWithApplication(RackApplication app) {
        app.destroy();
    }

    public RackApplication getErrorApplication() {
        return errorApplication;
    }

    public void destroy() {
        errorApplication.destroy();
        errorApplication = null;
    }

    public Ruby newRuntime() throws RackInitializationException {
        try {
            RubyInstanceConfig config = new RubyInstanceConfig();
            config.setClassCache(classCache);
            Ruby runtime = JavaEmbedUtils.initialize(new ArrayList(), config);
            runtime.getGlobalVariables().set("$servlet_context",
                    JavaEmbedUtils.javaToRuby(runtime, rackContext));
            runtime.evalScriptlet("require 'rack/handler/servlet'");
            return runtime;
        } catch (RaiseException re) {
            throw new RackInitializationException(re);
        }
    }

    public IRubyObject createApplicationObject(Ruby runtime) {
        return createRackServletWrapper(runtime, rackupScript);
    }

    public IRubyObject createErrorApplicationObject(Ruby runtime) {
        return createRackServletWrapper(runtime, "run JRuby::Rack::ErrorsApp.new");
    }

    public RackApplication newErrorApplication() {
        try {
            RackApplication app =
                    createApplication(new ApplicationObjectFactory() {
                public IRubyObject create(Ruby runtime) {
                    return createErrorApplicationObject(runtime);
                }
            });
            app.init();
            return app;
        } catch (final Exception e) {
            rackContext.log(
                "Warning: error application could not be initialized", e);
            return new RackApplication() {
                public void init() throws RackInitializationException { }
                public RackResponse call(RackEnvironment env) {
                    return new RackResponse() {
                        public int getStatus() { return 500; }
                        public Map getHeaders() { return Collections.EMPTY_MAP; }
                        public String getBody() {
                            return "Application initialization failed: "
                                    + e.getMessage();
                        }
                        public void respond(RackResponseEnvironment response) {
                            try {
                                response.defaultRespond(this);
                            } catch (IOException ex) {
                                rackContext.log("Error writing body", ex);
                            }
                        }
                    };
                }
                public void destroy() { }
                public Ruby getRuntime() { throw new UnsupportedOperationException("not supported"); }
            };
        }
    }

    protected IRubyObject createRackServletWrapper(Ruby runtime, String rackup) {
        return runtime.evalScriptlet(
                "load 'jruby/rack/boot/rack.rb'\n"
                +"Rack::Handler::Servlet.new(Rack::Builder.new {( "
                + rackup + "\n )}.to_app)");
    }

    private interface ApplicationObjectFactory {
        IRubyObject create(Ruby runtime);
    }

    private RackApplication createApplication(final ApplicationObjectFactory appfact)
            throws RackInitializationException {
        try {
            final Ruby runtime = newRuntime();
            return new DefaultRackApplication() {
                @Override
                public void init() throws RackInitializationException {
                    try {
                        setApplication(appfact.create(runtime));
                    } catch (RaiseException re) {
                        throw new RackInitializationException(re);
                    }
                }
                @Override
                public void destroy() {
                    JavaEmbedUtils.terminate(runtime);
                }
            };
        } catch (RackInitializationException rie) {
            throw rie;
        } catch (RaiseException re) {
            throw new RackInitializationException(re);
        }
    }

    /** Used only for testing; not part of the public API. */
    public String verify(Ruby runtime, String script) {
        try {
            return runtime.evalScriptlet(script).toString();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Used only by unit tests */
    public void setErrorApplication(RackApplication app) {
        this.errorApplication = app;
    }
}
