/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.builtins.helper;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.js.builtins.helper.JSRegExpExecIntlNodeGen.BuildGroupsObjectNodeGen;
import com.oracle.truffle.js.builtins.helper.JSRegExpExecIntlNodeGen.JSRegExpExecBuiltinNodeGen;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.access.IsJSClassNode;
import com.oracle.truffle.js.nodes.access.IsObjectNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.cast.JSToLengthNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.nodes.unary.IsCallableNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSObjectFactory;
import com.oracle.truffle.js.runtime.builtins.JSRegExp;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.TRegexUtil;
import com.oracle.truffle.regex.UnsupportedRegexException;

/**
 * Implements ES6 21.2.5.2.1 Runtime Semantics: RegExpExec ( R, S ).
 */
public abstract class JSRegExpExecIntlNode extends JavaScriptBaseNode {

    private final JSContext context;
    @Child private JSRegExpExecBuiltinNode regExpBuiltinNode;
    @Child private PropertyGetNode getExecNode;
    @Child private IsJSClassNode isJSRegExpNode;
    @Child private IsObjectNode isJSObjectNode;
    @Child private IsPristineObjectNode isPristineObjectNode;
    @Child private IsCallableNode isCallableNode = IsCallableNode.create();
    @Child private JSFunctionCallNode specialCallNode;
    private final ConditionProfile isPristine = ConditionProfile.createBinaryProfile();
    private final ConditionProfile isCallable = ConditionProfile.createBinaryProfile();
    private final ConditionProfile validResult = ConditionProfile.createBinaryProfile();
    private final ConditionProfile isRegExp = ConditionProfile.createBinaryProfile();

    JSRegExpExecIntlNode(JSContext context) {
        this.context = context;
    }

    public static JSRegExpExecIntlNode create(JSContext context) {
        return JSRegExpExecIntlNodeGen.create(context);
    }

    public abstract Object execute(DynamicObject regExp, String input);

    @Specialization
    Object doGeneric(DynamicObject regExp, String input) {
        if (context.getEcmaScriptVersion() >= 6) {
            if (isPristine.profile(isPristine(regExp))) {
                return executeBuiltIn(regExp, input);
            } else {
                Object exec = getExecProperty(regExp);
                if (isCallable.profile(isCallable(exec))) {
                    return callJSFunction(regExp, input, exec);
                }
            }
        }
        if (!isRegExp.profile(isJSRegExp(regExp))) {
            throw Errors.createTypeError("RegExp expected");
        }
        return executeBuiltIn(regExp, input);
    }

    private Object callJSFunction(DynamicObject regExp, String input, Object exec) {
        Object result = doCallJSFunction(exec, regExp, input);
        if (validResult.profile(result == Null.instance || isJSObject(result) && result != Undefined.instance)) {
            return result;
        }
        throw Errors.createTypeError("object or null expected");
    }

    private boolean isPristine(DynamicObject regExp) {
        if (isPristineObjectNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isPristineObjectNode = insert(IsPristineObjectNode.createRegExpExecAndMatch(context));
        }
        return isPristineObjectNode.execute(regExp);
    }

    private Object getExecProperty(DynamicObject regExp) {
        if (getExecNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            getExecNode = insert(PropertyGetNode.create("exec", false, context));
        }
        return getExecNode.getValue(regExp);
    }

    private boolean isCallable(Object obj) {
        if (isCallableNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isCallableNode = insert(IsCallableNode.create());
        }
        return isCallableNode.executeBoolean(obj);
    }

    private Object executeBuiltIn(DynamicObject regExp, String input) {
        if (regExpBuiltinNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            regExpBuiltinNode = insert(JSRegExpExecBuiltinNode.create(context));
        }
        return regExpBuiltinNode.execute(regExp, input);
    }

    private boolean isJSRegExp(DynamicObject regExp) {
        if (isJSRegExpNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isJSRegExpNode = insert(IsJSClassNode.create(JSRegExp.INSTANCE));
        }
        return isJSRegExpNode.executeBoolean(regExp);
    }

    private boolean isJSObject(Object obj) {
        if (isJSObjectNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            isJSObjectNode = insert(IsObjectNode.create());
        }
        return isJSObjectNode.executeBoolean(obj);
    }

    private Object doCallJSFunction(Object exec, DynamicObject regExp, String input) {
        if (specialCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            specialCallNode = insert(JSFunctionCallNode.createCall());
        }
        return specialCallNode.executeCall(JSArguments.createOneArg(regExp, exec, input));
    }

    public static IsJSClassNode createIsJSRegExpNode() {
        return IsJSClassNode.create(JSRegExp.INSTANCE);
    }

    /**
     * Ignores the {@code lastIndex} and {@code global} properties of the RegExp during matching.
     */
    @ImportStatic(JSRegExpExecIntlNode.class)
    public abstract static class JSRegExpExecIntlIgnoreLastIndexNode extends JavaScriptBaseNode {

        private final JSContext context;
        private final boolean doStaticResultUpdate;
        private final ValueProfile compiledRegexProfile = ValueProfile.createIdentityProfile();

        JSRegExpExecIntlIgnoreLastIndexNode(JSContext context, boolean doStaticResultUpdate) {
            this.context = context;
            this.doStaticResultUpdate = doStaticResultUpdate;
        }

        public static JSRegExpExecIntlIgnoreLastIndexNode create(JSContext context, boolean doStaticResultUpdate) {
            return JSRegExpExecIntlNodeGen.JSRegExpExecIntlIgnoreLastIndexNodeGen.create(context, doStaticResultUpdate);
        }

        public abstract TruffleObject execute(DynamicObject regExp, String input, long lastIndex);

        @Specialization
        TruffleObject doGeneric(DynamicObject regExp, String input, long lastIndex,
                        @Cached("create()") TRegexUtil.TRegexCompiledRegexAccessor compiledRegexAccessor,
                        @Cached("create()") TRegexUtil.TRegexResultAccessor regexResultAccessor,
                        @Cached("createIsJSRegExpNode()") IsJSClassNode isJSRegExpNode) {
            assert JSRegExp.isJSRegExp(regExp);
            TruffleObject compiledRegex = compiledRegexProfile.profile(JSRegExp.getCompiledRegexUnchecked(regExp, isJSRegExpNode.executeBoolean(regExp)));
            TruffleObject result = executeCompiledRegex(compiledRegex, input, lastIndex, compiledRegexAccessor);
            if (doStaticResultUpdate && context.isOptionRegexpStaticResult() && regexResultAccessor.isMatch(result)) {
                context.getRealm().setStaticRegexResult(compiledRegex, input, lastIndex, result);
            }
            return result;
        }
    }

    @ImportStatic({JSRegExp.class, JSRegExpExecIntlNode.class})
    public abstract static class BuildGroupsObjectNode extends JavaScriptBaseNode {

        public static BuildGroupsObjectNode create() {
            return BuildGroupsObjectNodeGen.create();
        }

        public abstract DynamicObject execute(JSContext context, DynamicObject regExp, TruffleObject regexResult);

        // We can reuse the cachedGroupsFactory even if the new groups factory is different, as long
        // as the compiledRegex is the same. This can happen if a new RegExp instance is repeatedly
        // created for the same regular expression.
        @Specialization(guards = "getGroupsFactoryUnchecked(regExp, isJSRegExpNode.executeBoolean(regExp)) == cachedGroupsFactory || getCompiledRegexUnchecked(regExp, isJSRegExpNode.executeBoolean(regExp)) == cachedCompiledRegex")
        static DynamicObject doCachedGroupsFactory(JSContext context,
                        @SuppressWarnings("unused") DynamicObject regExp,
                        TruffleObject regexResult,
                        @Cached("getCompiledRegex(regExp)") @SuppressWarnings("unused") TruffleObject cachedCompiledRegex,
                        @Cached("getGroupsFactory(regExp)") JSObjectFactory cachedGroupsFactory,
                        @Cached("createIsJSRegExpNode()") @SuppressWarnings("unused") IsJSClassNode isJSRegExpNode) {
            return doIt(context, cachedGroupsFactory, regexResult);
        }

        @Specialization
        @TruffleBoundary
        static DynamicObject doVaryingGroupsFactory(JSContext context, DynamicObject regExp, TruffleObject regexResult) {
            return doIt(context, JSRegExp.getGroupsFactory(regExp), regexResult);
        }

        private static DynamicObject doIt(JSContext context, JSObjectFactory groupsFactory, TruffleObject regexResult) {
            if (groupsFactory == null) {
                return Undefined.instance;
            } else {
                return JSObject.create(context, groupsFactory, regexResult);
            }
        }
    }

    // implements ES6 21.2.5.2.2 Runtime Semantics: RegExpBuiltinExec ( R, S )
    @ImportStatic({JSRegExp.class, JSRegExpExecIntlNode.class})
    public abstract static class JSRegExpExecBuiltinNode extends JavaScriptBaseNode {

        private final JSContext context;
        private final ConditionProfile invalidLastIndex = ConditionProfile.createBinaryProfile();
        private final ConditionProfile match = ConditionProfile.createCountingProfile();
        private final ConditionProfile stickyProfile = ConditionProfile.createBinaryProfile();
        private final int ecmaScriptVersion;

        @Child private JSToLengthNode toLengthNode;
        @Child private PropertyGetNode getLastIndexNode;
        @Child private PropertySetNode setLastIndexNode;
        @Child private TRegexUtil.TRegexCompiledRegexAccessor compiledRegexAccessor = TRegexUtil.TRegexCompiledRegexAccessor.create();
        @Child private TRegexUtil.TRegexFlagsAccessor flagsAccessor = TRegexUtil.TRegexFlagsAccessor.create();
        @Child private TRegexUtil.TRegexResultAccessor regexResultAccessor = TRegexUtil.TRegexResultAccessor.create();
        @Child private BuildGroupsObjectNode groupsBuilder;

        JSRegExpExecBuiltinNode(JSContext context) {
            this.context = context;
            ecmaScriptVersion = context.getEcmaScriptVersion();
        }

        public static JSRegExpExecBuiltinNode create(JSContext context) {
            return JSRegExpExecBuiltinNodeGen.create(context);
        }

        private Object getEmptyResult() {
            return ecmaScriptVersion >= 6 ? Null.instance : TRegexUtil.getTRegexEmptyResult();
        }

        public abstract Object execute(DynamicObject regExp, String input);

        @Specialization(guards = "getCompiledRegexUnchecked(regExp, isJSRegExpNode.executeBoolean(regExp)) == cachedCompiledRegex")
        Object doCached(DynamicObject regExp, String input,
                        @Cached("getCompiledRegex(regExp)") TruffleObject cachedCompiledRegex,
                        @Cached("createIsJSRegExpNode()") @SuppressWarnings("unused") IsJSClassNode isJSRegExpNode) {
            return doExec(regExp, cachedCompiledRegex, input);
        }

        @Specialization(replaces = "doCached")
        Object doDynamic(DynamicObject regExp, String input,
                        @Cached("createIsJSRegExpNode()") IsJSClassNode isJSRegExpNode) {
            return doExec(regExp, JSRegExp.getCompiledRegexUnchecked(regExp, isJSRegExpNode.executeBoolean(regExp)), input);
        }

        // Implements 21.2.5.2.2 Runtime Semantics: RegExpBuiltinExec ( R, S )
        private Object doExec(DynamicObject regExp, TruffleObject compiledRegex, String input) {
            TruffleObject flags = compiledRegexAccessor.flags(compiledRegex);
            boolean global = flagsAccessor.global(flags);
            boolean sticky = ecmaScriptVersion >= 6 && flagsAccessor.sticky(flags);
            long lastIndex = getLastIndex(regExp);
            if (global || sticky) {
                if (invalidLastIndex.profile(lastIndex < 0 || lastIndex > input.length())) {
                    setLastIndex(regExp, 0);
                    return getEmptyResult();
                }
            } else {
                lastIndex = 0;
            }

            TruffleObject result = executeCompiledRegex(compiledRegex, input, lastIndex, compiledRegexAccessor);
            if (context.isOptionRegexpStaticResult() && regexResultAccessor.isMatch(result)) {
                context.getRealm().setStaticRegexResult(compiledRegex, input, lastIndex, result);
            }
            if (match.profile(regexResultAccessor.isMatch(result))) {
                if (stickyProfile.profile(sticky && regexResultAccessor.captureGroupStart(result, 0) != lastIndex)) {
                    // matcher should never have advanced that far!
                    setLastIndex(regExp, 0);
                    return getEmptyResult();
                }
                if (global || sticky) {
                    setLastIndex(regExp, regexResultAccessor.captureGroupEnd(result, 0));
                }
                if (ecmaScriptVersion < 6) {
                    return result;
                }
                DynamicObject groups = getGroupsObject(regExp, result);
                return getMatchResult(result, input, groups);
            } else {
                if (ecmaScriptVersion < 8 || global || sticky) {
                    setLastIndex(regExp, 0);
                }
                return getEmptyResult();
            }
        }

        // converts RegexResult into DynamicObject
        private DynamicObject getMatchResult(TruffleObject result, String inputStr, DynamicObject groups) {
            return JSArray.createLazyRegexArray(context, regexResultAccessor.groupCount(result), result, inputStr, groups);
        }

        // builds the object containing the matches of the named capture groups
        private DynamicObject getGroupsObject(DynamicObject regExp, TruffleObject result) {
            if (groupsBuilder == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                groupsBuilder = insert(BuildGroupsObjectNode.create());
            }
            return groupsBuilder.execute(context, regExp, result);
        }

        private long getLastIndex(DynamicObject regExp) {
            if (getLastIndexNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getLastIndexNode = insert(PropertyGetNode.create(JSRegExp.LAST_INDEX, false, context));
            }
            Object lastIndex = getLastIndexNode.getValue(regExp);
            if (ecmaScriptVersion < 6) {
                return JSRuntime.intValueVirtual(JSRuntime.toNumber(lastIndex));
            } else {
                if (toLengthNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    toLengthNode = insert(JSToLengthNode.create());
                }
                return toLengthNode.executeLong(lastIndex);
            }
        }

        private void setLastIndex(DynamicObject regExp, int value) {
            if (setLastIndexNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                setLastIndexNode = insert(PropertySetNode.create(JSRegExp.LAST_INDEX, false, context, true));
            }
            setLastIndexNode.setValueInt(regExp, value);
        }
    }

    private static TruffleObject executeCompiledRegex(TruffleObject compiledRegex, String input, long fromIndex,
                    TRegexUtil.TRegexCompiledRegexAccessor compiledRegexAccessor) {
        try {
            return compiledRegexAccessor.exec(compiledRegex, input, fromIndex);
        } catch (UnsupportedRegexException e) {
            CompilerDirectives.transferToInterpreter();
            throw Errors.createError(e.getMessage());
        }
    }
}
