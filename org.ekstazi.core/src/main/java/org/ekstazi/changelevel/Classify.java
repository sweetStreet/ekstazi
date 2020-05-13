package org.ekstazi.changelevel;

import com.google.gson.GsonBuilder;
import org.ekstazi.asm.ClassReader;
import org.ekstazi.asm.ClassWriter;
import org.ekstazi.asm.Opcodes;
import org.ekstazi.hash.Hasher;
import org.ekstazi.util.FileUtil;
import com.google.gson.Gson;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;


import static org.ekstazi.hash.BytecodeCleaner.removeDebugInfo;

public class Classify {
    static String CLASS = "Class";
    static String METHOD = "Method";
    static String MULMETHODS = "MultiMethods";
    static String OTHER = "Other";

    public void executeCommands(String projectPath, String sha, int numSHA){

        Map<String,String> resLinkedHashMap = new LinkedHashMap<String, String>();
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();

        String[] shalist = bashCommand(projectPath, "git rev-list --first-parent -n " + (numSHA+1) + " " + sha + " | tail -r").split("\n");

        String preSHA = shalist[0];

        for(int i = 1; i<shalist.length; i++){
            Map<String, String> jsonObject = new LinkedHashMap<>();
            String curSHA = shalist[i];

            bashCommand(projectPath, "git checkout "+preSHA);
            bashCommand(projectPath, "mvn test-compile -Drat.skip");
            bashCommand(projectPath, "cp -rf target/classes preclasses");
            bashCommand(projectPath, "cp -rf target/test-classes pretestclasses");
            bashCommand(projectPath, "git clean -f");
            bashCommand(projectPath, "rm -rf target");
            bashCommand(projectPath, "git checkout "+curSHA);
            bashCommand(projectPath, "mvn clean test-compile -Drat.skip");

            System.out.println(preSHA);
            System.out.println(curSHA);

            String[] javafiles = bashCommand(projectPath,"git diff "+preSHA+" "+curSHA+" --name-only '*.java'").split("\n");
            for(String javaFile:javafiles){
                if(javaFile.equals("")){
                    continue;
                }

                String changeLevel = CLASS;
                String classFile = javaFile.replace(".java", ".class");
                String preClassPath = "";
                String curClassPath = "";
                if(classFile.startsWith("src/main/java")){
                    preClassPath = projectPath + "/preclasses" + classFile.substring(13);
                    curClassPath = projectPath + "/target/classes" + classFile.substring(13);
                }else if(classFile.startsWith("src/test/java")){
                    preClassPath = projectPath + "/pretestclasses" + classFile.substring(13);
                    curClassPath = projectPath + "/target/test-classes" + classFile.substring(13);
                }

//                bashCommand("~/Desktop", "echo "+preSHA+" "+curSHA+" >> log.txt");
//                bashCommand("~/Desktop", "echo "+preClassPath+" >> log.txt");
//                bashCommand("~/Desktop", "echo "+curClassPath+" >> log.txt");

                File pref = new File(preClassPath);
                File curf = new File(curClassPath);
                if(!pref.exists() || !curf.exists()){
                    System.out.println(preClassPath+" "+pref.exists());
                    System.out.println(curClassPath+" "+curf.exists());
                    System.out.println("file does not exist");
                    changeLevel = CLASS;
                }else{
                    try {
                        byte[] preBytes = FileUtil.readFile(new File(preClassPath));
//                        BytecodeCleaner.MulArray preMulArray = BytecodeCleaner.removeDebugInfoUpdate(preBytes);
                        byte[] preCleanBytes = removeDebugInfo(preBytes);

                        byte[] curBytes = FileUtil.readFile(new File(curClassPath));
//                        BytecodeCleaner.MulArray curMulArray = BytecodeCleaner.removeDebugInfoUpdate(curBytes);
                        byte[] curCleanBytes = removeDebugInfo(curBytes);

//                        bashCommand("~/Desktop", "echo "+convertByteArray(preMulArray.baosArray)+" >> log.txt");
//                        bashCommand("~/Desktop", "echo "+convertByteArray(curMulArray.baosArray)+" >> log.txt");

                        if(convertByteArray(preCleanBytes).equals(convertByteArray(curCleanBytes))){
                            changeLevel = OTHER;
                        }else{
//                            if(!convertByteArray(preMulArray.fieldBaosArray).equals(convertByteArray(curMulArray.fieldBaosArray))){
//                                changeLevel = CLASS;
//                            }else if(!convertByteArray(preMulArray.annotationBaosArray).equals(convertByteArray(curMulArray.annotationBaosArray))){
//                                changeLevel = CLASS;
//                            }else{
                            TreeMap<String, Integer> preTreeMap = getMethodBody(preBytes);
                            TreeMap<String, Integer> curTreeMap = getMethodBody(curBytes);

                            if (preTreeMap.size()!= curTreeMap.size()){
                                System.out.println("number of methods is different");
                                changeLevel = CLASS;
                            }else {
                                int mCount = 0;
                                for (String key : preTreeMap.keySet()) {
//                                    System.out.println(key);
//                                    System.out.println(preTreeMap.get(key));
//                                    System.out.println(curTreeMap.get(key));
                                    if(!curTreeMap.containsKey(key)){
                                        changeLevel = CLASS;
                                        break;
                                    }else{
                                        if(curTreeMap.get(key) != preTreeMap.get(key)){
                                            mCount += 1;
                                        }
                                    }
                                }
                                if(mCount == 1){
                                    changeLevel = METHOD;
                                }else if(mCount > 1){
                                    changeLevel = MULMETHODS;
                                }
                            }
//                            }
                        }
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                jsonObject.put(javaFile, changeLevel);
            }

            resLinkedHashMap.put(curSHA, gson.toJson(jsonObject, LinkedHashMap.class));

            bashCommand(projectPath, "rm -rf preclasses");
            bashCommand(projectPath, "rm -rf pretestclasses");
            bashCommand(projectPath, "rm -rf target");
            preSHA = curSHA;
        }

        // generate json file
        String[] projectNameList = projectPath.split("/");
        String projectName = projectNameList[projectNameList.length-1];
        String res = gson.toJson(resLinkedHashMap,LinkedHashMap.class);
        try {
            Files.write(Paths.get(projectName), res.getBytes());
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public Long convertByteArray(byte[] bytes){
        return Hasher.hashString(new String(bytes, StandardCharsets.UTF_8));
    }

    public TreeMap getMethodBody(byte[] bytes){
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        MyClassVisitor visitor =new MyClassVisitor(Opcodes.ASM6, writer);
        reader.accept(visitor, ClassReader.SKIP_DEBUG);
        return visitor.getTreeMap();
    }

    public String bashCommand(String projectPath, String command){
        String[] commands = {"bash", "-c", "cd "+projectPath+";"+command};
        try {
            Runtime r = Runtime.getRuntime();
            Process p = r.exec(commands);
            p.waitFor();
            BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line = "";
            while ((line = b.readLine()) != null) {
                sb.append(line+"\n");
            }
            b.close();
            return sb.toString();
        } catch (Exception e) {
            System.err.println("Failed to execute bash with command: " + command);
            e.printStackTrace();
        }
        return "";
    }

    public static void main(String[] args){
        Classify c = new Classify();
        c.executeCommands("/Users/liuyu/Desktop/ctaxonomy/ekstazi/_downloads/commons-codec", "24059898", 50);
        c.executeCommands("/Users/liuyu/Desktop/ctaxonomy/ekstazi/_downloads/commons-compress", "dfa9ed37", 50);
        c.executeCommands("/Users/liuyu/Desktop/ctaxonomy/ekstazi/_downloads/commons-beanutils", "37e9ee0a", 50);
        c.executeCommands("/Users/liuyu/Desktop/ctaxonomy/ekstazi/_downloads/commons-pool", "b6775ade", 50);
        c.executeCommands("/Users/liuyu/Desktop/ctaxonomy/ekstazi/_downloads/fastjson", "4c516b12", 50);

    }
}
