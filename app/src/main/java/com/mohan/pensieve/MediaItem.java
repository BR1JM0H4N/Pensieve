package com.mohan.pensieve;

public class MediaItem {
    public enum Type { IMAGE, VIDEO, AUDIO, OTHER }

    private String filePath;
    private String fileName;
    private Type type;
    private long size;
    private long timestamp;
    private String sourceUrl;

    public MediaItem(String filePath, String fileName, Type type, long size, long timestamp, String sourceUrl) {
        this.filePath = filePath;
        this.fileName = fileName;
        this.type = type;
        this.size = size;
        this.timestamp = timestamp;
        this.sourceUrl = sourceUrl;
    }

    public String getFilePath() { return filePath; }
    public String getFileName() { return fileName; }
    public Type getType() { return type; }
    public long getSize() { return size; }
    public long getTimestamp() { return timestamp; }
    public String getSourceUrl() { return sourceUrl; }

    public String getFormattedSize() {
        if (size < 1024) return size + " B";
        else if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        else return String.format("%.1f MB", size / (1024.0 * 1024));
    }
}
