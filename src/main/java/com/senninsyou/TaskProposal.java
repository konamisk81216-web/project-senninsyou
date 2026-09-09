package com.senninsyou;

public class TaskProposal {

    private String summary;
    private String nextTask;
    private String priority;
    private String assignedAgent;

    public TaskProposal(
        String summary,
        String nextTask,
        String priority,
        String assignedAgent) {

    this.summary = summary;
    this.nextTask = nextTask;
    this.priority = priority;
    this.assignedAgent = assignedAgent;
}

public String getSummary() {
    return summary;
}

public String getNextTask() {
    return nextTask;
}

public String getPriority() {
    return priority;
}

public String getAssignedAgent() {
    return assignedAgent;
}

}