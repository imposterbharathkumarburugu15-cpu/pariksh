package com.doomedams.app.database.repository

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.entity.IncidentCaseEntity
import com.doomedams.app.database.entity.IncidentDecisionEntity
import com.doomedams.app.database.entity.PolicyActionEntity

class IncidentRepository(private val database: AppDatabase) {

    suspend fun createIncident(incident: IncidentCaseEntity): IncidentCaseEntity {
        database.incidentDao().insertIncident(incident)
        return incident
    }

    suspend fun getIncident(incidentId: String): IncidentCaseEntity? {
        return database.incidentDao().getIncidentById(incidentId)
    }

    suspend fun getIncidentsForAttempt(attemptId: String): List<IncidentCaseEntity> {
        return database.incidentDao().getIncidentsByAttempt(attemptId)
    }

    suspend fun recordDecision(decision: IncidentDecisionEntity): IncidentDecisionEntity {
        database.incidentDao().insertDecision(decision)
        return decision
    }

    suspend fun getDecisionsForIncident(incidentId: String): List<IncidentDecisionEntity> {
        return database.incidentDao().getDecisionsForIncident(incidentId)
    }

    suspend fun recordPolicyAction(action: PolicyActionEntity): PolicyActionEntity {
        database.incidentDao().insertPolicyAction(action)
        return action
    }

    suspend fun getPolicyActionsForIncident(incidentId: String): List<PolicyActionEntity> {
        return database.incidentDao().getPolicyActionsForIncident(incidentId)
    }
}
