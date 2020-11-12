package jira_cloud.listeners.set_field_value_based_on.hierarchy

import kong.unirest.Unirest

/**
 * ### Hierarchy ###
 *  Story, Bug, Design Story, Dev Story -> Epic -> Initiative -> Theme
 * 'Effort Sizing' is filled in manually in Story, Bug, Design Story, Dev Story. 'Estimated days to complete' is
 * calculated with help of Jira Cloud Automation module.
 * ### Script logic ###
 * For Epic:
 *  - 'Estimated days to complete' = Sum of Estimated days to complete' of all issues in Epic;
 *  - 'Remaining days to complete' = Epic's 'Estimated days to complete' - 'Estimated days to complete' of resolved
 *  issues in Epic.
 * For Initiative:
 *  - 'Estimated days to complete' = Sum of 'Estimated days to complete' of epics in this initiative;
 *  - 'Remaining days to complete' = Initiative's 'Estimated days to complete' - 'Estimated days to complete' of
 *  resolved Epics in Initiative.
 * For Theme:
 *  - 'Estimated days to complete' = Sum of 'Estimated days to complete' of initiatives in this theme;
 *  - 'Remaining days to complete' = Theme's 'Estimated days to complete' - 'Estimated days to complete' of resolved
 *  Initiatives in Theme.
 * ### Triggers ###
 * - 'Effort Sizing' is updated
 * - any issue is added/removed/reassigned to/from/between Epic/s;
 * - any issue is added/removed/reassigned to/from/between Initiative/s;
 * - any issue is added/removed/reassigned to/from/between Theme/s;
 * - resolution change.
 */

final String EPIC_LINK = "Epic Link"
final String PARENT_LINK = "Parent Link"
final String EFFORT_SIZING = "Effort Sizing"
final String ESTIMATED_DAYS_TO_COMPLETE = "Estimated days to complete"
final String REMAINING_DAYS_TO_COMPLETE = "Remaining days to complete"

def customFields = Unirest.get("/rest/api/2/field").asObject(List).body.findAll { (it as Map).custom } as List<Map>
def epicLinkId = customFields.find { it.name == EPIC_LINK }?.id
def parentLinkId = customFields.find { it.name == PARENT_LINK }?.id
def estimatedDaysToCompleteId = customFields.find { it.name == ESTIMATED_DAYS_TO_COMPLETE }?.id
def remainingDaysToCompleteId = customFields.find { it.name == REMAINING_DAYS_TO_COMPLETE }?.id
def issueType = issue.fields.issuetype.name as String

def calculateEpicIssue = { String issueKey ->
    logger.info "Epic Issue Calculation: ${issueKey} "
    def jqlIssuesInEpic = """ "${EPIC_LINK}" = ${issueKey} """
    def issuesInEpic = executeSearch(jqlIssuesInEpic, 0, 500)
    def estimatedDaysToCompleteVal = issuesInEpic.findResults { it.fields[estimatedDaysToCompleteId] }.sum()
    logger.info "estimatedDaysToCompleteVal ${estimatedDaysToCompleteVal}"
    def estimatedDaysToCompleteResolvedIssues = issuesInEpic.findResults {
        it.fields.resolution ? it.fields[estimatedDaysToCompleteId] : null
    }.sum()
    logger.info "estimatedDaysToCompleteResolvedIssues ${estimatedDaysToCompleteResolvedIssues}"
    def remainingDaysToCompleteVal = null
    if ((estimatedDaysToCompleteVal || estimatedDaysToCompleteVal == 0) &&
            (estimatedDaysToCompleteResolvedIssues || estimatedDaysToCompleteResolvedIssues == 0))
        remainingDaysToCompleteVal = estimatedDaysToCompleteVal - estimatedDaysToCompleteResolvedIssues
    def epicFieldsVals = [:]
    epicFieldsVals.put(estimatedDaysToCompleteId, estimatedDaysToCompleteVal)
    epicFieldsVals.put(remainingDaysToCompleteId, remainingDaysToCompleteVal)
    def status = setFields(issueKey, epicFieldsVals)
    logger.info "Status ${status}"
}

def calculateRoadmapIssue = { String issueKey ->
    logger.info "Roadmap Issue Calculation: ${issueKey}"
    def jqlChildIssues = """ "${PARENT_LINK}" = ${issueKey} """
    def childIssues = executeSearch(jqlChildIssues, 0, 500)
    def estimatedDaysToCompleteVal = childIssues.findResults { it.fields[estimatedDaysToCompleteId] }.sum()
    logger.info "estimatedDaysToCompleteVal ${estimatedDaysToCompleteVal}"
    def estimatedDaysToCompleteResolvedIssues = childIssues.findResults {
        it.fields.resolution ? it.fields[estimatedDaysToCompleteId] : null
    }.sum()
    logger.info "estimatedDaysToCompleteResolvedIssues ${estimatedDaysToCompleteResolvedIssues}"
    def remainingDaysToCompleteVal = null
    if ((estimatedDaysToCompleteVal || estimatedDaysToCompleteVal == 0) &&
            (estimatedDaysToCompleteResolvedIssues || estimatedDaysToCompleteResolvedIssues == 0))
        remainingDaysToCompleteVal = estimatedDaysToCompleteVal - estimatedDaysToCompleteResolvedIssues
    def roadmapIssueFieldsVals = [:]
    roadmapIssueFieldsVals.put(estimatedDaysToCompleteId, estimatedDaysToCompleteVal)
    roadmapIssueFieldsVals.put(remainingDaysToCompleteId, remainingDaysToCompleteVal)
    def status = setFields(issueKey, roadmapIssueFieldsVals)
    logger.info "Status ${status}"
}

logger.info "${issue_event_type_name} ${issueType} ${issue.key}"
switch (issueType) {
    case "Story":
    case "Bug":
    case "Design Story":
    case "Dev Story":
        def shouldRun = changelog.items.field.any { it.toString() in [EFFORT_SIZING, EPIC_LINK, "resolution"] }
        logger.info "shouldRun ${shouldRun}"
        if (!shouldRun) return
        def epicKeys = []
        def epicLinkChange = changelog.items.find { (it as Map).field == EPIC_LINK } as Map
        if (epicLinkChange) {
            epicKeys << epicLinkChange.fromString
            epicKeys << epicLinkChange.toString
        }
        epicKeys.removeAll { it == null }
        if (epicKeys.empty) epicKeys << issue.fields[epicLinkId]
        epicKeys.each { String epicKey ->
            calculateEpicIssue(epicKey)
            def initiativeKey = getIssue(epicKey).fields[parentLinkId].data.key
            logger.info "Initiative ${initiativeKey}"
            if (!initiativeKey) return
            calculateRoadmapIssue(initiativeKey)
            def themeKey = getIssue(initiativeKey).fields[parentLinkId].data.key
            logger.info "Theme ${themeKey}"
            if (!themeKey) return
            calculateRoadmapIssue(themeKey)
        }
        break
    case "Epic":
        def shouldRun = changelog.items.field.any { it.toString() in [PARENT_LINK, "resolution"] }
        logger.info "shouldRun ${shouldRun}"
        if (!shouldRun) return
        def initiativeKeys = []
        def parentLinkChange = changelog.items.find { (it as Map).field == PARENT_LINK } as Map
        if (parentLinkChange) {
            initiativeKeys << parentLinkChange.fromString
            initiativeKeys << parentLinkChange.toString
        }
        initiativeKeys.removeAll { it == null }
        if (initiativeKeys.empty) initiativeKeys << issue.fields[parentLinkId].data.key
        initiativeKeys.each { String initiativeKey ->
            calculateRoadmapIssue(initiativeKey)
            def themeKey = getIssue(initiativeKey).fields[parentLinkId].data.key
            logger.info "Theme ${themeKey}"
            if (!themeKey) return
            calculateRoadmapIssue(themeKey)
        }
        break
    case "Initiative":
        def shouldRun = changelog.items.field.any { it.toString() in [PARENT_LINK, "resolution"] }
        logger.info "shouldRun ${shouldRun}"
        if (!shouldRun) return
        def themeKeys = []
        def parentLinkChange = changelog.items.find { (it as Map).field == PARENT_LINK } as Map
        if (parentLinkChange) {
            themeKeys << parentLinkChange.fromString
            themeKeys << parentLinkChange.toString
        }
        themeKeys.removeAll { it == null }
        if (themeKeys.empty) themeKeys << issue.fields[parentLinkId].data.key
        themeKeys.each { calculateRoadmapIssue(it) }
        break
}

static int setFields(String issueKey, Map fieldsAndVals) {
    def result = Unirest.put("/rest/api/2/issue/${issueKey}")
            .queryString("overrideScreenSecurity", Boolean.TRUE)
            .queryString("notifyUsers", Boolean.FALSE)
            .header("Content-Type", "application/json")
            .body([fields: fieldsAndVals]).asString()
    return result.status
}

static List executeSearch(String jqlQuery, int startAt, int maxResults) {
    def searchRequest = Unirest.get("/rest/api/2/search")
            .queryString("jql", jqlQuery)
            .queryString("startAt", startAt)
            .queryString("maxResults", maxResults)
            .asObject(Map)
    searchRequest.status == 200 ? searchRequest.body.issues as List : null
}

static Map getIssue(String issueKey) {
    Unirest.get("/rest/api/2/issue/${issueKey}").asObject(Map).body
}