package ai.deepar.video_processing_example;

public class Effect {

    private final String name;
    private final String path;
    private final String thumbnailPath;

    public Effect(String name, String path, String thumbnailPath) {
        this.name = name;
        this.path = path;
        this.thumbnailPath = thumbnailPath;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getThumbnailPath() {
        return thumbnailPath;
    }
}
