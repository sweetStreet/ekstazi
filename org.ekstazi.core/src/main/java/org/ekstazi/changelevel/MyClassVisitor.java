package org.ekstazi.changelevel;

import org.ekstazi.asm.*;
import org.ekstazi.asm.util.*;
import org.ekstazi.hash.Hasher;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.TreeMap;

public class MyClassVisitor extends ClassVisitor {

    TreeMap<String, Long> treeMap;

    public MyClassVisitor(int api, ClassVisitor cv) {
        super(api, cv);
        treeMap = new TreeMap<>();
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
        Printer p = new Textifier(Opcodes.ASM6) {
            @Override
            public void visitMethodEnd() {
                StringWriter sw = new StringWriter();
                print(new PrintWriter(sw));
                getText().clear();
                treeMap.put(name+desc, Hasher.hashString(sw.toString()));
            }
        };
        return new TraceMethodVisitor(mv, p);
    }

    public TreeMap getTreeMap(){
        return treeMap;
    }

}