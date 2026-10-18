package com.plc.directmaintenance.model

data class ChecklistItem(
    val id: String,
    val title: String,
    val description: String,
    val isCompleted: Boolean = false,
    val criticalStep: Boolean = false,
    val ppeRequired: List<String> = emptyList(),
    val toolsRequired: List<String> = emptyList(),
    val estimatedMinutes: Int = 5,
    val completedAt: Long? = null,
    val completedBy: String? = null
)

data class MaintenanceChecklist(
    val id: String,
    val alertId: String,
    val alertTitle: String,
    val machineName: String,
    val plcTag: String,
    val procedureCode: String,
    val title: String,
    val assignedTechnician: String,
    val items: List<ChecklistItem>,
    val technicianNotes: String = "",
    val isCompleted: Boolean = false
)
