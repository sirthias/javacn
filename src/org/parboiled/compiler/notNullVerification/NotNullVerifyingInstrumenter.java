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

import org.objectweb.asm.*;
import org.apache.tools.ant.Task;

/**
 * @author ven
 * @noinspection HardCodedStringLiteral
 */
public class NotNullVerifyingInstrumenter extends ClassAdapter {

    static final String ENUM_CLASS_NAME = "java/lang/Enum";
    static final String CONSTRUCTOR_NAME = "<init>";

    final Task antTask;
    boolean myIsModification = false;
    boolean myIsNotStaticInner = false;
    String myClassName;
    String mySuperName;

    public NotNullVerifyingInstrumenter(Task antTask, ClassVisitor classVisitor) {
        super(classVisitor);
        this.antTask = antTask;
    }

    public boolean isModification() {
        return myIsModification;
    }

    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        myClassName = name;
        mySuperName = superName;
    }

    public void visitInnerClass(String name, String outerName, String innerName, int access) {
        super.visitInnerClass(name, outerName, innerName, access);
        if (myClassName.equals(name)) {
            myIsNotStaticInner = (access & Opcodes.ACC_STATIC) == 0;
        }
    }

    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        Type[] args = Type.getArgumentTypes(desc);
        Type returnType = Type.getReturnType(desc);
        int startParameter = getStartParameterIndex(name);
        MethodVisitor v = cv.visitMethod(access, name, desc, signature, exceptions);
        return new MyMethodAdapter(this, v, args, returnType, access, startParameter, name);
    }

    private int getStartParameterIndex(String name) {
        int result = 0;
        if (CONSTRUCTOR_NAME.equals(name)) {
            if (mySuperName.equals(ENUM_CLASS_NAME)) {
                result += 2;
            }
            if (myIsNotStaticInner) {
                result += 1;
            }
        }
        return result;
    }

}
