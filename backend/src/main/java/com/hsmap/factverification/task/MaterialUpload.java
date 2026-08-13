package com.hsmap.factverification.task;

import java.io.IOException;
import java.io.InputStream;

/** 控制器向任务服务传递的上传材料，不暴露 servlet multipart 类型。 */
public record MaterialUpload(
        String originalFileName,
        String mediaType,
        long size,
        String authorizationNote,
        String message,
        InputStreamSource content) {

    /** 兼容既有文件上传调用；新对话 API 会显式传入 message。 */
    public MaterialUpload(
            String originalFileName, String mediaType, long size, String authorizationNote, InputStreamSource content) {
        this(originalFileName, mediaType, size, authorizationNote, "", content);
    }

    /** 统一把可空消息投影为空串，避免业务层反复判空。 */
    public String safeMessage() {
        return message == null ? "" : message.strip();
    }

    /** 每次调用打开一个新的输入流，服务负责及时关闭。 */
    @FunctionalInterface
    public interface InputStreamSource {
        InputStream open() throws IOException;
    }
}
