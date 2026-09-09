package com.senninsyou;

public class Task {

    private int id;
    private String taskName;
    private String status;
    private String priority;
    private String assignedAgent;

    

    public Task(int id, String taskName, String status, String priority, String assignedAgent) {
        this.id = id;
        this.taskName = taskName;
        this.status = status;
        this.priority = priority;
        this.assignedAgent = assignedAgent;
    }

    public int getId() {
    return id;
}

public String getTaskName() {
    return taskName;
}

public String getStatus() {
    return status;
}

public String getPriority() {
    return priority;
}

public String getAssignedAgent() {
    return assignedAgent;
    }
}
