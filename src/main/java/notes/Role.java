package notes;

public enum Role {
    OWNER,
    EDITOR,
    VIEWER;

    public boolean canWrite() {
        return this != VIEWER;
    }
}
