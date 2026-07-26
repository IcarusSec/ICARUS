package icarus.core;

public class FindingRecord {
    private final Finding finding;
    private int count;
    private boolean suppressed;

    public FindingRecord(Finding finding) {
        this.finding = finding;
        this.count = 1;
        this.suppressed = false;
    }

    public Finding getFinding() { return finding; }
    public int getCount() { return count; }
    public void incrementCount() { this.count++; }
    
    public boolean isSuppressed() { return suppressed; }
    public void setSuppressed(boolean suppressed) { this.suppressed = suppressed; }
}
