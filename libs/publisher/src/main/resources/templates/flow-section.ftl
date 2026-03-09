### ${flow.flowName}

${flow.narrative.executiveSummary!""}

${flow.narrative.detailedNarrative!""}

<#list flow.narrative.diagrams![] as diagram>
#### ${diagram.title}

```mermaid
${diagram.mermaidCode}
```

</#list>

<#if flow.narrative.keyFindings?? && (flow.narrative.keyFindings?size > 0)>
#### Key Findings

<#list flow.narrative.keyFindings as finding>
- **${finding.title}** (${finding.severity}): ${finding.description}
</#list>
</#if>

<#if flow.narrative.openQuestions?? && (flow.narrative.openQuestions?size > 0)>
#### Open Questions

<#list flow.narrative.openQuestions as q>
- ${q}
</#list>
</#if>

<#if flow.narrative.recommendedNextSteps??>
#### Recommended Next Steps

${flow.narrative.recommendedNextSteps}
</#if>
