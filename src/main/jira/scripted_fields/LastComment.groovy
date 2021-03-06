package jira.scripted_fields
//Script Location: Sript Field
//Shows last comment for each JIRA issue. Usefull in search for issues.
//String output was rendered to wiki.

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.renderer.IssueRenderContext

def commentManager = ComponentAccessor.commentManager
def comment = commentManager?.getLastComment(issue)
def lastComment = null
if (comment) {
    lastComment = comment.body
    def wikiRenderer = ComponentAccessor.rendererManager.getRendererForType("atlassian-wiki-renderer")
    def renderContext = new IssueRenderContext(issue)
    lastComment = wikiRenderer.render(lastComment, renderContext)
}
return lastComment