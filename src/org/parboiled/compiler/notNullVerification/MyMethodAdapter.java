/*
 * Copyright 2000-2008 JetBrains s.r.o.
 * Modified in 2009 by Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.parboiled.compiler.notNullVerification;

import org.apache.tools.ant.Project;
import org.objectweb.asm.*;

import java.util.ArrayList;

class MyMethodAdapter extends MethodAdapter {

    private final ArrayList<Object> myNotNullParams;
    private boolean myIsNotNull;
    public Label myThrowLabel;
    private Label myStartGeneratedCodeLabel;
    private final NotNullVerifyingInstrumenter instrumenter;
    private final Type[] args;
    private final Type returnType;
    private final int access;
    private final int startParameter;
    private final String name;

    public MyMethodAdapter(NotNullVerifyingInstrumenter instrumenter, MethodVisitor v, Type[] args, Type returnType,
                           int access, int startParameter, String name) {
        super(v);
        this.instrumenter = instrumenter;
        this.args = args;
        this.returnType = returnType;
        this.access = access;
        this.startParameter = startParameter;
        this.name = name;
        myNotNullParams = new ArrayList<Object>();
        myIsNotNull = false;
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, String anno, boolean visible) {
        AnnotationVisitor av = mv.visitParameterAnnotation(parameter, anno, visible);
        if (isNotNull(anno)) {
            if (isReferenceType(args[parameter])) {
                myNotNullParams.add(parameter);
            } else {
                instrumenter.antTask.log(toOrdinalString(parameter) + " parameter of method " + getFullMethodName() +
                        " carries a @NotNull annotation but is not a reference type, ignoring annotation...",
                        Project.MSG_WARN);
            }
        }
        return av;
    }

    @Override
    public AnnotationVisitor visitAnnotation(String anno, boolean isRuntime) {
        AnnotationVisitor av = mv.visitAnnotation(anno, isRuntime);
        if (isNotNull(anno)) {
            if (isReferenceType(returnType)) {
                myIsNotNull = true;
            } else {
                instrumenter.antTask.log("Method " + getFullMethodName() +
                        " carries a @NotNull annotation but does not return a reference type, ignoring annotation...",
                        Project.MSG_WARN);
            }
        }
        return av;
    }

    @Override
    public void visitCode() {
        if (myNotNullParams.size() > 0) {
            myStartGeneratedCodeLabel = new Label();
            mv.visitLabel(myStartGeneratedCodeLabel);
        }
        for (Object myNotNullParam : myNotNullParams) {
            int var = ((access & Opcodes.ACC_STATIC) == 0) ? 1 : 0;
            int param = (Integer) myNotNullParam;
            for (int i = 0; i < param + startParameter; ++i) {
                var += args[i].getSize();
            }
            mv.visitVarInsn(Opcodes.ALOAD, var);

            Label end = new Label();
            mv.visitJumpInsn(Opcodes.IFNONNULL, end);

            generateThrow("java/lang/IllegalArgumentException", toOrdinalString(param) + " argument of method " +
                    getFullMethodName() + "(...) corresponds to @NotNull parameter and must not be null", end);
        }
    }

    private String toOrdinalString(int param) {
        switch (param) {
            case 0:
                return "1st";
            case 1:
                return "2nd";
            case 2:
                return "3rd";
            default:
                return param + "th";
        }
    }

    @Override
    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        boolean isParameter = isStatic ? index < args.length : index <= args.length;
        mv.visitLocalVariable(name, desc, signature,
                (isParameter && myStartGeneratedCodeLabel != null) ? myStartGeneratedCodeLabel : start, end,
                index);
    }

    @Override
    public void visitInsn(int opcode) {
        if (opcode == Opcodes.ARETURN && myIsNotNull) {
            mv.visitInsn(Opcodes.DUP);
            if (myThrowLabel == null) {
                Label skipLabel = new Label();
                mv.visitJumpInsn(Opcodes.IFNONNULL, skipLabel);
                myThrowLabel = new Label();
                mv.visitLabel(myThrowLabel);
                generateThrow("java/lang/IllegalStateException",
                        "@NotNull method " + getFullMethodName() + " must not return null", skipLabel);
            } else {
                mv.visitJumpInsn(Opcodes.IFNULL, myThrowLabel);
            }
        }

        mv.visitInsn(opcode);
    }

    private String getFullMethodName() {
        return instrumenter.myClassName.replace('/', '.') + '.' + name;
    }

    private void generateThrow(String exceptionClass, String descr, Label end) {
        String exceptionParamClass = "(Ljava/lang/String;)V";
        mv.visitTypeInsn(Opcodes.NEW, exceptionClass);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(descr);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, exceptionClass, NotNullVerifyingInstrumenter.CONSTRUCTOR_NAME,
                exceptionParamClass);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(end);

        instrumenter.myIsModification = true;
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        try {
            super.visitMaxs(maxStack, maxLocals);
        }
        catch (ArrayIndexOutOfBoundsException e) {
            throw new ArrayIndexOutOfBoundsException(
                    "maxs processing failed for method " + name + ": " + e.getMessage());
        }
    }

    private static boolean isReferenceType(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private static boolean isNotNull(String anno) {
        return anno.endsWith("/NotNull;") ||
                anno.endsWith("/NonNull;") ||
                anno.endsWith("/Notnull;") ||
                anno.endsWith("/Nonnull;");
    }

}
