package com.hnu.muwu.service;

import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.hnu.muwu.bean.FinalFile;
import com.hnu.muwu.bean.MyFile;
import com.hnu.muwu.config.GlobalVariables;
import com.hnu.muwu.mapper.FileMapper;
import com.hnu.muwu.utiles.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service("fileService")
public class FileServiceImpl implements FileService {

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private PhotoTagService photoTagService;

    @Autowired
    private FileTagService fileTagService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Override
    public int insertFile(FinalFile file) {
        return fileMapper.insertFile(file.getUserId(), file.getFilename(), file.getFilePath(), file.getFileType(),
                file.getUploadTime(), file.getSize(), file.getTag(), file.getDescription());
    }

    @Override
    public FinalFile getFileByName (String name, Integer userId) {
        List<FinalFile> files = fileMapper.getFileByUserIdAndFilename(userId, name);
        if (files.isEmpty()) {
            return null;
        }
        return files.getFirst();
    }

    @Override
    public List<FinalFile> searchFilesByNameLike(String keyword, Integer userId) {
        return fileMapper.getFileByKeywordAndUserId(keyword, userId);
    }

    @Override
    public List<FinalFile> searchFilesByQianwen(String keyword, Integer userId) {
        List<FinalFile> files = fileMapper.getAllFilesByUserId(userId);
        String question = "请根据下面的疑问信息和用户的查找输入，给出可能符合条件的结果的文件名称，用英文逗号（,）分割多个文件名，回答形式如：file1,file2,file3。"
                + "回答应只有如示例所示的文件名，不可包括其他任何内容，若没有符合条件的文件，请回答“没有满足条件的文件”\n" + keyword + "\n" + files.toString();

        try {
            String response = QianwenHelper.processMessage(question);

            if (response == null || response.isEmpty() || response.equals("没有满足条件的文件")) {
                return new ArrayList<>();
            }

            List<String> matchedFilenames = Arrays.asList(response.split(","))
                    .stream()
                    .map(String::trim)
                    .collect(Collectors.toList());

            return files.stream()
                    .filter(file -> matchedFilenames.contains(file.getFilename()))
                    .collect(Collectors.toList());

        } catch (NoApiKeyException | InputRequiredException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getTag (String filePath, Integer userId, String fileType) {
        if (fileType.equals("png") || fileType.equals("jpg")) {
            List<String> tags = photoTagService.getTagsByUserId(userId);
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", filePath);
            message.put("operation", "get_photo_tag");
            message.put("text_descriptions", tags);
            try {
                Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
                if (result != null) {
                    return photoTagService.getTagByName(userId, (String) result.get("result"));
                }
            } catch (IOException | TimeoutException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else if (fileType.equals("md") || fileType.equals("txt")) {
            String content = FileHelper.readFileContent(filePath);
            return fileTagService.getTagByContent(userId, filePath, content);
        } else if (fileType.equals("docx")) {
            try {
                String content = WordHelper.readDocx(filePath);
                return fileTagService.getTagByContent(userId, filePath, content);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @Override
    public String getText (String filePath, String fileType) {
        if (fileType.equals("png") || fileType.equals("jpg")) {
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", filePath);
            message.put("operation", "generate_caption");
            try {
                Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
                if (result != null) {
                    return TranslateHelper.translateEnglish((String) result.get("result"));
                }
            } catch (IOException | TimeoutException | InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null;
        } else if (fileType.equals("md") || fileType.equals("txt")) {
            String content = FileHelper.readFileContent(filePath);
            return this.getFileDescription(filePath, content);
        } else if (fileType.equals("docx")) {
            try {
                String content = WordHelper.readDocx(filePath);
                return this.getFileDescription(filePath, content);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @Override
    public String getPath(Integer userId, String tag, MyFile file, String text){
        String fileTree = FileTreeHelper.generateFileTree(GlobalVariables.rootPath + File.separator + userId);
        HashMap<String, Object> message = new HashMap<>();
        message.put("file-infomation", file);
        message.put("text-descriptions", text);
        message.put("file-tree", fileTree);
        message.put("tag", tag);
        String question = "请根据下面的文件信息，结合用户已有的文件结构和文件存放习惯，综合考虑用户习惯和查找检索方便等因素，"
                + "直接给出此文件合适的存放路径(路径需包含文件名，从用户文件起始，如：100000/images/image.png),回答不要包含其他任何的内容" + message.toString();
        try {
            return QianwenHelper.processMessage(question);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        return null;
    }

    @Override
    public MyFile getAndDeleteMyFile(Integer userId) {
        String key = userId.toString();
        String json = redisTemplate.opsForValue().get(key);
        System.out.println("json: " + json);
        if (json != null) {
            try {
                redisTemplate.delete(key);
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(json, MyFile.class);
            } catch (IOException e) {
                throw new RuntimeException("反序列化 MyFile 失败", e);
            }
        }
        return null;
    }

    @Override
    public String fileOperatorExtend(String filePath, String type) {
        if (type.equals("png") || type.equals("jpg")) {
            return photoExtend(filePath);
        } else if (type.equals("md")) {
            return mdExtend(filePath);
        } else if (type.equals("docx")) {
            return wordExtend(filePath);
        }
        return null;
    }


    public String mdExtend(String filePath) {
        redisTemplate.opsForValue().set(filePath, "Generate_mindmap");
        return "检测到上传markdown文件,是否生成思维导图";
    }

    public String wordExtend(String filePath) {
        redisTemplate.opsForValue().set(filePath, "convert_word_to_pdf");
        return "检测到上传word文件,是否转化为pdf";
    }


    @Override
    public String generateMindmapFromMd(String filePath, Integer userId) {
        String text = FileHelper.readFileContent(filePath);
        String question = "分析下面的文件内容，整理生成大纲，注意，回答应只包含大纲的内容，不可包含其他任何内容\n" + text;
        try {
            String answer = QianwenHelper.processMessage(question);

            File originalFile = new File(filePath);
            String parentDirectory = originalFile.getParent(); // 获取文件所在目录
            String originalFilename = originalFile.getName(); // 获取原始文件名（含扩展名）

            String baseName = originalFilename;
            int lastDotIndex = originalFilename.lastIndexOf('.');
            if (lastDotIndex != -1) {
                baseName = originalFilename.substring(0, lastDotIndex); // 去掉扩展名
            }

            String newFilename = baseName + "_s.md";
            File newFile = new File(parentDirectory, newFilename);

            try (FileWriter writer = new FileWriter(newFile)) {
                writer.write(answer);
            }

            String newFilePath = newFile.getAbsolutePath();
            String outputDir = FileHelper.getFileDirectory(newFilePath);
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", newFilePath);
            message.put("output_dir", outputDir);
            message.put("operation", "Generate_mindmap");
            Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
            if (result != null) {
                if (result.get("status").equals("success")) {
                    Path htmlPath = Paths.get((String) result.get("html_path"));
                    MyFile myFile = FileHelper.createMyFileFromPath(htmlPath.toString(), userId);
                    String tag = fileTagService.getTagByContent(userId, htmlPath.toString(), FileHelper.readFileContent(htmlPath.toString()));
                    String description = getFileDescription(htmlPath.toString(), FileHelper.readFileContent(htmlPath.toString()));
                    assert myFile != null;
                    this.insertFile(new FinalFile(myFile, tag, description));
                    FileHelper.deleteFile(newFilePath);
                    return (String) result.get("html_path");
                } else {
                    return (String) result.get("result");
                }
            } else {
                return "未收到处理结果或处理超时";
            }

        } catch (NoApiKeyException | InputRequiredException | InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败", e);
        }
    }

    @Override
    public String generateMindmapFromMdS(String filePath, Integer userId) {
        String text = FileHelper.readFileContent(filePath);
        try {
            String outputDir = FileHelper.getFileDirectory(filePath);
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", filePath);
            message.put("output_dir", outputDir);
            message.put("operation", "Generate_mindmap");
            Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
            if (result != null) {
                if (result.get("status").equals("success")) {
                    Path htmlPath = Paths.get((String) result.get("html_path"));
                    MyFile myFile = FileHelper.createMyFileFromPath(htmlPath.toString(), userId);
                    String tag = fileTagService.getTagByContent(userId, htmlPath.toString(), FileHelper.readFileContent(htmlPath.toString()));
                    String description = getFileDescription(htmlPath.toString(), FileHelper.readFileContent(htmlPath.toString()));
                    assert myFile != null;
                    this.insertFile(new FinalFile(myFile, tag, description));
                    return (String) result.get("html_path");
                } else {
                    return (String) result.get("result");
                }
            } else {
                return "未收到处理结果或处理超时";
            }

        } catch (InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败", e);
        }
    }


    public String photoExtend(String filePath) {
        try {
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", filePath);
            message.put("operation", "is_rich_text");
            Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
            if (result != null) {
                String status = (String) result.get("status");
                Boolean re = (Boolean) result.get("result");
                if (status.equals("success")) {
                    if (re) {
                        Path path = Paths.get(filePath);
                        redisTemplate.opsForValue().set(path.toString(), "OCR");
                        return "检测到上传图片为富文本图片，是否提取图片文本";
                    } else {
                        return null;
                    }
                } else {
                    System.out.println("文件处理失败，错误信息: " + re);
                    return null;
                }
            } else {
                System.out.println("未收到处理结果或处理超时");
            }
        } catch (IOException | TimeoutException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public Map<String, Object> photoOCR(String filePath, Integer userId) {
        try {
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", filePath);
            message.put("operation", "OCR");
            Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
            if (result != null) {
                String status = (String) result.get("status");
                if (status.equals("success")) {
                    Map<String, Object> re = new HashMap<>();
                    String text = (String) result.get("result");
                    String path = (String) result.get("file_path");
                    String question = "请整理格式化下面的OCR输出内容，注意，最后的回答只能包含格式化后的内容，不可以包含任何其他内容\n" + text;
                    String res = QianwenHelper.processMessage(question);
                    Files.writeString(
                            Paths.get(path),
                            res,
                            StandardOpenOption.TRUNCATE_EXISTING
                    );
                    MyFile OCRResult = FileHelper.createMyFileFromPath((String) result.get("file_path"), userId);
                    String tag = fileTagService.getTagByContent(userId, (String) result.get("file_path"), FileHelper.readFileContent((String) result.get("file_path")));
                    String description = this.getFileDescription((String) result.get("file_path"), FileHelper.readFileContent((String) result.get("file_path")));
                    assert OCRResult != null;
                    this.insertFile(new FinalFile(OCRResult, tag, description));
                    re.put("res", res);
                    re.put("result", (String) result.get("file_path"));
                    return re;
                } else {
                    return null;
                }
            }
        } catch (IOException | TimeoutException | InterruptedException | NoApiKeyException | InputRequiredException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public String getFileDescription(String filePath, String content) {
        try {
            String question = "请根据下面的文件内容，生成一个50字左右的概述，回答应只包含概述内容，不可包含任何其他内容\n" + content;
            System.out.println(question);
            return QianwenHelper.processMessage(question);
        } catch (NoApiKeyException | InputRequiredException e) {
            throw new RuntimeException(e);
        }
    }

    public String SR(String filePath) {
        try {
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", filePath);
            message.put("operation", "SR");
            Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
            if (result != null) {
                String status = (String) result.get("status");
                if (status.equals("success")) {
                    return (String) result.get("result");
                } else {
                    return null;
                }
            } else {
                System.out.println("未收到处理结果或处理超时");
                return null;
            }
        } catch (IOException | TimeoutException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public String convertWordToPdf(String filePath, int userId) {
        try {
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", filePath);
            message.put("operation", "convert_word_to_pdf");
            Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
            if (result != null) {
                String status = (String) result.get("status");
                if (status.equals("success")) {
                    String resultPath = (String) result.get("result");
                    MyFile resultFile = FileHelper.createMyFileFromPath(resultPath, userId);
                    String content = FileHelper.readFileContent((String) result.get("file_path"));
                    String tag = fileTagService.getTagByContent(userId, resultPath, content);
                    String description = this.getFileDescription(resultPath, content);
                    assert resultFile != null;
                    this.insertFile(new FinalFile(resultFile, tag, description));
                    FileHelper.deleteFile((String) result.get("file_path"));
                    return resultPath;
                }
            } else {
                System.out.println("未收到处理结果或处理超时");
            }
        } catch (IOException | TimeoutException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Override
    public String getPdfText(String filePath, int userId) {
        try {
            HashMap<String, Object> message = new HashMap<>();
            message.put("file_path", filePath);
            message.put("operation", "extract_pdf_text");
            Map<String, Object> result = MessageQueueHelper.sendMessageAndGetResult(message);
            if (result != null) {
                String status = (String) result.get("status");
                if (status.equals("success")) {
                    MyFile myFile = FileHelper.createMyFileFromPath((String) result.get("result"), userId);
                    String content = FileHelper.readFileContent((String) result.get("result"));
                    String tag = fileTagService.getTagByContent(userId, myFile.getFilePath(), content);
                    String description = this.getFileDescription((String) result.get("result"), content);
                    FinalFile  finalFile = new FinalFile(myFile, tag, description);
                    this.insertFile(finalFile);
                    return (String) result.get("result");
                } else {
                    return "提取pdf文本失败";
                }
            } else {
                System.out.println("未收到处理结果或处理超时");
                return "未收到处理结果或处理超时";
            }
        } catch (IOException | InterruptedException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * 删除文件（包括物理文件和数据库记录）
     * @param userId 用户ID
     * @param filePath 文件相对路径
     * @return true-删除成功，false-删除失败
     */
    @Override
    public boolean deleteFile(Integer userId, String filePath) {
        try {
//            // 1. 先删除物理文件
//            String fullPath = GlobalVariables.rootPath + File.separator + filePath;
//            boolean fileDeleted = FileHelper.deleteFile(fullPath);
//
//            if (!fileDeleted) {
//                return false;
//            }

            // 2. 删除数据库记录
            int result = fileMapper.deleteFileByUserIdAndPath(userId, filePath);
            System.out.println(result);

            return result > 0;

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
            return false;
        }
    }
}
