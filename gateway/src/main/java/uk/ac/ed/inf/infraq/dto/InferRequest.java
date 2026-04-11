package uk.ac.ed.inf.infraq.dto;

public class InferRequest {
    private String prompt;
    private String taskType = "chat";
    private int priority = 0;

    public InferRequest() {}

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
}
