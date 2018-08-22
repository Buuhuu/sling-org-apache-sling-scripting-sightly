/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 ******************************************************************************/
package org.apache.sling.scripting.sightly.impl.engine;

import java.io.Reader;
import java.io.StringReader;
import java.util.Set;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScriptConstants;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.scripting.api.AbstractSlingScriptEngine;
import org.apache.sling.scripting.api.ScriptNameAware;
import org.apache.sling.scripting.api.resource.ScriptingResourceResolverProvider;
import org.apache.sling.scripting.sightly.SightlyException;
import org.apache.sling.scripting.sightly.compiler.CompilationResult;
import org.apache.sling.scripting.sightly.compiler.CompilationUnit;
import org.apache.sling.scripting.sightly.compiler.CompilerMessage;
import org.apache.sling.scripting.sightly.compiler.SightlyCompiler;
import org.apache.sling.scripting.sightly.impl.engine.compiled.SourceIdentifier;
import org.apache.sling.scripting.sightly.impl.utils.BindingsUtils;
import org.apache.sling.scripting.sightly.java.compiler.GlobalShadowCheckBackendCompiler;
import org.apache.sling.scripting.sightly.java.compiler.JavaClassBackendCompiler;
import org.apache.sling.scripting.sightly.java.compiler.JavaEscapeUtils;
import org.apache.sling.scripting.sightly.java.compiler.JavaImportsAnalyzer;
import org.apache.sling.scripting.sightly.java.compiler.RenderUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTL Script engine
 */
public class SightlyScriptEngine extends AbstractSlingScriptEngine implements Compilable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SightlyScriptEngine.class);
    private static final String NO_SCRIPT = "NO_SCRIPT";

    private SightlyCompiler sightlyCompiler;
    private SightlyJavaCompilerService javaCompilerService;
    private final SightlyEngineConfiguration configuration;
    private final ScriptingResourceResolverProvider scriptingResourceResolverProvider;
    private final SlingJavaImportsAnalyser importsAnalyser;

    SightlyScriptEngine(ScriptEngineFactory scriptEngineFactory,
                               SightlyCompiler sightlyCompiler,
                               SightlyJavaCompilerService javaCompilerService,
                               SightlyEngineConfiguration configuration,
                               ScriptingResourceResolverProvider scriptingResourceResolverProvider) {
        super(scriptEngineFactory);
        this.sightlyCompiler = sightlyCompiler;
        this.javaCompilerService = javaCompilerService;
        this.configuration = configuration;
        this.scriptingResourceResolverProvider = scriptingResourceResolverProvider;
        importsAnalyser = new SlingJavaImportsAnalyser();
    }

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        return compile(new StringReader(script));
    }

    @Override
    public CompiledScript compile(final Reader script) throws ScriptException {
        return internalCompile(script, null);
    }

    @Override
    public Object eval(Reader reader, ScriptContext scriptContext) throws ScriptException {
        checkArguments(reader, scriptContext);
        Bindings bindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
        SlingBindings slingBindings = new SlingBindings();
        slingBindings.putAll(bindings);
        final SlingHttpServletRequest request = slingBindings.getRequest();
        if (request == null) {
            throw new SightlyException("Missing SlingHttpServletRequest from ScriptContext.");
        }
        final Object oldValue = request.getAttribute(SlingBindings.class.getName());
        try {
            request.setAttribute(SlingBindings.class.getName(), slingBindings);
            SightlyCompiledScript compiledScript = internalCompile(reader, scriptContext);
            return compiledScript.eval(scriptContext);
        } catch (Exception e) {
            throw new ScriptException(e);
        } finally {
            request.setAttribute(SlingBindings.class.getName(), oldValue);
        }
    }

    private SightlyCompiledScript internalCompile(final Reader script, ScriptContext scriptContext) throws ScriptException {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(((SightlyScriptEngineFactory) getFactory()).getClassLoader());
        try {
            String sName = NO_SCRIPT;
            if (script instanceof ScriptNameAware) {
                sName = ((ScriptNameAware) script).getScriptName();
            }
            if (sName.equals(NO_SCRIPT)) {
                sName = getScriptName(scriptContext);
            }
            final String scriptName = sName;
            CompilationUnit compilationUnit = new CompilationUnit() {
                @Override
                public String getScriptName() {
                    return scriptName;
                }

                @Override
                public Reader getScriptReader() {
                    return script;
                }
            };
            Object renderUnit = null;
            if (scriptContext != null) {
                renderUnit = scriptContext.getAttribute("precompiled.unit", SlingScriptConstants.SLING_SCOPE);
            }
            if (renderUnit == null) {
                JavaClassBackendCompiler javaClassBackendCompiler = new JavaClassBackendCompiler(importsAnalyser);
                GlobalShadowCheckBackendCompiler shadowCheckBackendCompiler = null;
                if (scriptContext != null) {
                    Bindings bindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
                    Set<String> globals = bindings.keySet();
                    shadowCheckBackendCompiler = new GlobalShadowCheckBackendCompiler(javaClassBackendCompiler, globals);
                }
                CompilationResult result = shadowCheckBackendCompiler == null ? sightlyCompiler.compile(compilationUnit,
                        javaClassBackendCompiler) : sightlyCompiler.compile(compilationUnit, shadowCheckBackendCompiler);
                if (result.getWarnings().size() > 0) {
                    for (CompilerMessage warning : result.getWarnings()) {
                        LOGGER.warn("Script {} {}:{}: {}", warning.getScriptName(), warning.getLine(), warning.getColumn(),
                                warning.getMessage());
                    }
                }
                if (result.getErrors().size() > 0) {
                    CompilerMessage error = result.getErrors().get(0);
                    throw new ScriptException(error.getMessage(), error.getScriptName(), error.getLine(), error.getColumn());
                }
                SourceIdentifier sourceIdentifier = new SourceIdentifier(configuration, scriptName);
                String javaSourceCode = javaClassBackendCompiler.build(sourceIdentifier);
                renderUnit = javaCompilerService.compileSource(sourceIdentifier, javaSourceCode);
            }
            if (renderUnit instanceof RenderUnit) {
                return new SightlyCompiledScript(this, (RenderUnit) renderUnit);
            } else {
                throw new SightlyException("Expected a RenderUnit.");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    private void checkArguments(Reader reader, ScriptContext scriptContext) {
        if (reader == null) {
            throw new NullPointerException("Reader cannot be null");
        }
        if (scriptContext == null) {
            throw new NullPointerException("ScriptContext cannot be null");
        }
    }

    private String getScriptName(ScriptContext scriptContext) {
        if (scriptContext != null) {
            Bindings bindings = scriptContext.getBindings(ScriptContext.ENGINE_SCOPE);
            String scriptName = (String) bindings.get(ScriptEngine.FILENAME);
            if (scriptName != null && !"".equals(scriptName)) {
                return scriptName;
            }
            SlingScriptHelper sling = BindingsUtils.getHelper(bindings);
            if (sling != null) {
                return sling.getScript().getScriptResource().getPath();
            }
        }
        return NO_SCRIPT;
    }

    /**
     * This custom imports analyser makes sure that no import statements are generated for repository-based use objects, since these are
     * not compiled ahead of the HTL scripts.
     */
    class SlingJavaImportsAnalyser implements JavaImportsAnalyzer {
        @Override
        public boolean allowImport(String importedClass) {
            for (String searchPath : scriptingResourceResolverProvider.getRequestScopedResourceResolver().getSearchPath()) {
                String subPackage = JavaEscapeUtils.makeJavaPackage(searchPath);
                if (importedClass.startsWith(subPackage)) {
                    return false;
                }
            }
            return true;
        }
    }
}
