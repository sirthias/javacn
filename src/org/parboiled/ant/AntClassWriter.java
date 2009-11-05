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

package org.parboiled.ant;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * @author yole
 */
public class AntClassWriter extends ClassWriter {
    private final ClassLoader myClassLoader;

    public AntClassWriter(int flags, ClassLoader classLoader) {
        super(flags);
        myClassLoader = classLoader;
    }

    public AntClassWriter(ClassReader classReader, int flags, ClassLoader classLoader) {
        super(classReader, flags);
        myClassLoader = classLoader;
    }

    protected String getCommonSuperClass(String type1, String type2) {
        Class<?> c;
        Class<?> d;
        try {
            c = myClassLoader.loadClass(type1.replace('/', '.'));
            d = myClassLoader.loadClass(type2.replace('/', '.'));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e.getMessage());
        }
        if (c.isAssignableFrom(d)) {
            return type1;
        }
        if (d.isAssignableFrom(c)) {
            return type2;
        }
        if (c.isInterface() || d.isInterface()) {
            return "java/lang/Object";
        } else {
            do {
                c = c.getSuperclass();
            } while (!c.isAssignableFrom(d));
            return c.getName().replace('.', '/');
        }
    }
}