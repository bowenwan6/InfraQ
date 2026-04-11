package uk.ac.ed.inf.infraq.dto;

public class BenchmarkConfig {
    private int numRequests = 20;
    private String strategy = "continuous";
    private int numSlots = 4;

    public BenchmarkConfig() {}

    public int getNumRequests() { return numRequests; }
    public void setNumRequests(int numRequests) { this.numRequests = numRequests; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public int getNumSlots() { return numSlots; }
    public void setNumSlots(int numSlots) { this.numSlots = numSlots; }
}
