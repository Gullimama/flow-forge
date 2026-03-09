# ${document.title}

> Generated: ${generatedAtFormatted} UTC
> Snapshot: ${document.snapshotId}

## Table of Contents

1. [Executive Summary](#executive-summary)
<#list document.flowSections as flow>
${flow?counter + 1}. [${flow.flowName}](#${flow.anchor})
</#list>
${document.flowSections?size + 2}. [Risk Matrix](#risk-matrix)
${document.flowSections?size + 3}. [Migration Roadmap](#migration-roadmap)
${document.flowSections?size + 4}. [Appendices](#appendices)

---

## Executive Summary

${document.executiveSummary.overview}

| Metric | Value |
|---|---|
| Total Flows Analyzed | ${document.executiveSummary.totalFlows} |
| Services Involved | ${document.executiveSummary.totalServices} |
| Critical Risks | ${document.executiveSummary.criticalRisks} |
| High Risks | ${document.executiveSummary.highRisks} |

### Top Findings

<#list document.executiveSummary.topFindings as finding>
- ${finding}
</#list>

### Recommended Approach

${document.executiveSummary.recommendedApproach}

---

<#list document.flowSections as flow>
<#include "flow-section.ftl">

---

</#list>

## Risk Matrix

| Flow | Risk | Severity | Category | Mitigation |
|---|---|---|---|---|
<#list document.riskMatrix.entries as entry>
| ${entry.flowName} | ${entry.riskDescription} | ${entry.severity} | ${entry.category} | ${entry.mitigation} |
</#list>

## Migration Roadmap

<#list document.roadmap.phases as phase>
### Phase ${phase.order}: ${phase.name} (${phase.duration})

**Flows:** ${phase.flows?join(", ")}

**Deliverables:**
<#list phase.deliverables as deliverable>
- ${deliverable}
</#list>

</#list>

**Estimated Total Duration:** ${document.roadmap.totalEstimatedDuration}
**Recommended Team Size:** ${document.roadmap.recommendedTeamSize}

## Appendices

<#list document.appendices as appendix>
### ${appendix.title}

${appendix.content}

</#list>
