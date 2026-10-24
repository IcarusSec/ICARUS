/**
 * Starter report profiles for the ICARUS template builder.
 * Part of ICARUS, MIT License.
 */
(function (root, factory) {
  var profile = (root && root.IcarusProfile) || (typeof require === "function" ? require("./profile.js") : null);
  var api = factory(profile);
  if (typeof module === "object" && module.exports) module.exports = api;
  if (root) root.ICARUS_TEMPLATES = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function (P) {
  var all = P.CATALOG.map(function (item) { return item.id; });
  var quick = ["COVER", "EXECUTIVE_SUMMARY", "ASSESSMENT_SCOPE", "RISK_RATING", "VULNERABILITY_SUMMARY", "FINDINGS", "REMEDIATION"];

  function withChart(state, title) {
    var chart = P.makeBlock({
      type: "chart",
      chart: "bar",
      binding: "findingsBySeverity",
      title: title
    });
    var at = 0;
    state.sections.forEach(function (section, index) {
      if (section.id === "VULNERABILITY_SUMMARY") at = index;
    });
    state.sections.splice(at, 0, chart);
    return state;
  }

  return [
    withChart(P.makeTemplate({
      id: "web-app",
      name: "Web application pentest",
      locale: "en",
      preset: "icarus",
      coverStyle: "GRADIENT_HERO",
      findingLayout: "ELEVATED_CARD",
      severityScale: "cvss",
      enabled: all,
      required: ["COVER", "CONFIDENTIALITY", "EXECUTIVE_SUMMARY", "FINDINGS", "RISK_RATING"],
      fields: {
        companyName: "Example Security",
        documentTitle: "Web Application Penetration Test",
        project: "Example Project",
        component: "app.example",
        environment: "Staging",
        method: "Gray box, OWASP WSTG",
        team: "Product team",
        requester: "Project sponsor",
        owner: "System owner",
        author: "A. Analyst",
        reviewer: "Reviewer",
        classification: "Confidential",
        assessmentPeriod: "October 2026"
      },
      sections: {
        EXECUTIVE_SUMMARY: {
          title: "Executive summary",
          content: "This report presents the web application penetration test of **{{component}}**, requested by {{requester}} and performed by {{author}} ({{team}}) in the **{{environment}}** environment during {{assessment_period}}.\n\nThe test identified **{{finding_count}}** findings ({{finding_types}}). Each item below includes the impact and a remediation path. Ratings describe technical severity; {{owner}} can raise or lower priority from business context.\n\nThe goal is a clear view of the risk for engineering and for the people who accept it."
        },
        ASSESSMENT_SCOPE: {
          title: "Scope",
          content: "## In scope\n\n- Application: {{component}}\n- Environment: {{environment}}\n- Window: {{assessment_period}}\n- Accounts provided by {{requester}}\n\n## Out of scope\n\n- Denial of service and resource exhaustion\n- Social engineering and physical access\n- Third-party services not owned by {{owner}}\n- Production data beyond what was required to prove a finding\n\n## Rules of engagement\n\nTesting was authorized by {{requester}} and limited to the targets above. Destructive payloads were not used."
        },
        METHODOLOGY: {
          title: "Methodology",
          content: "Testing followed the OWASP Web Security Testing Guide (WSTG), with the OWASP Application Security Verification Standard used as a coverage checklist. Work was manual, in Burp Suite, with ICARUS used for parameter checks, session handling, and evidence.\n\nMethod recorded for this engagement: **{{method}}**.\n\n1. Reconnaissance and mapping of the authenticated and anonymous surfaces\n2. Session, authentication, and access-control testing\n3. Input handling, injection, and server-side request behavior\n4. Business logic, configuration, and transport review\n5. Validation of each finding, then reporting\n\nA finding is included only when it was reproduced. Scanner output alone is not a finding."
        },
        REMEDIATION: {
          title: "Remediation",
          content: "## Priorities\n\n1. Fix Critical and High findings before the next release, or inside the agreed window.\n2. Schedule Medium findings into the current development cycle.\n3. Track Low and Info items as hardening, not as emergencies.\n\n## Retest\n\nAfter fixes reach **{{environment}}**, ask for a retest of the closed items. ICARUS retest mode can mark evidence as fixed or not fixed in the follow-up.\n\nPrepared by {{author}}. Reviewed by {{reviewer}}. Classification: {{classification}}."
        },
        APPENDIX: {
          title: "Appendix",
          content: "## References\n\n- OWASP Top 10\n- OWASP Web Security Testing Guide\n- OWASP Application Security Verification Standard\n- NIST National Vulnerability Database\n- CWE (Common Weakness Enumeration)\n\n## Document control\n\n- Classification: {{classification}}\n- Author: {{author}}\n- Reviewer: {{reviewer}}\n- Approver: {{approver}}\n- Version: {{version}}\n- Date: {{date}}\n- Project: {{project}}"
        }
      }
    }), "Findings by severity"),
    P.makeTemplate({
      id: "internal-network",
      name: "Internal network assessment",
      locale: "en",
      preset: "navy",
      coverStyle: "HEADER_BAND",
      findingLayout: "TABULAR",
      severityScale: "cvss",
      enabled: all,
      required: ["COVER", "CONFIDENTIALITY", "TABLE_OF_CONTENTS", "EXECUTIVE_SUMMARY", "FINDINGS"],
      fields: {
        companyName: "Example Security",
        documentTitle: "Internal Network Assessment",
        project: "Example Office Network",
        component: "10.10.0.0/16",
        environment: "Internal",
        method: "Gray box, PTES",
        team: "Infrastructure",
        requester: "IT sponsor",
        owner: "Network owner",
        author: "A. Analyst",
        reviewer: "Reviewer",
        classification: "Confidential",
        assessmentPeriod: "October 2026"
      },
      sections: {
        EXECUTIVE_SUMMARY: {
          title: "Executive summary",
          content: "This report presents the internal network assessment of **{{component}}**, requested by {{requester}} and performed by {{author}} for {{team}} during {{assessment_period}}.\n\nTesting was limited to the in-scope ranges in **{{environment}}**. **{{finding_count}}** validated findings are included ({{finding_types}}). Hosts were not taken down, and exploits were stopped once control or data access was proven.\n\nUse the findings table for the work list and the detail section for evidence and fix guidance."
        },
        ASSESSMENT_SCOPE: {
          title: "Scope",
          content: "## In scope\n\n- Address space: {{component}}\n- Environment: {{environment}}\n- Window: {{assessment_period}}\n- Credentials supplied by {{requester}}, where provided\n\n## Out of scope\n\n- Networks outside the ranges above\n- Denial of service, flooding, and password spraying beyond a small check\n- Personal devices and guest wireless, unless listed in writing\n- Systems owned by a third party rather than {{owner}}\n\n## Rules of engagement\n\n{{requester}} authorized the test. Production impact was avoided. A finding that needed an exploit was proven on as few hosts as possible."
        },
        METHODOLOGY: {
          title: "Methodology",
          content: "The engagement followed the Penetration Testing Execution Standard (PTES) for an internal assessment. Method: **{{method}}**.\n\n1. Host and service discovery inside the agreed ranges\n2. Enumeration of exposed management, file, and directory services\n3. Validation of patch, configuration, and authentication weaknesses\n4. Limited credential and lateral-movement checks where the rules allowed them\n5. Evidence capture and de-duplication before reporting\n\nICARUS, running in Burp Suite, supported evidence and the written report. Unauthenticated scanning was not treated as permission to disrupt a host."
        },
        REMEDIATION: {
          title: "Remediation",
          content: "## Priorities\n\n1. Remove or restrict services that expose administrative access on {{component}}.\n2. Patch or isolate hosts with a Critical or High finding before the next change window.\n3. Close Medium findings in the infrastructure backlog. Track Low and Info as hardening.\n\n## Retest\n\nWhen changes are in **{{environment}}**, request a retest of the affected hosts only. Keep {{owner}} on the distribution list.\n\nPrepared by {{author}}. Reviewed by {{reviewer}}. Classification: {{classification}}."
        },
        APPENDIX: {
          title: "Appendix",
          content: "## References\n\n- Penetration Testing Execution Standard (PTES)\n- CIS Benchmarks relevant to the observed platforms\n- NIST National Vulnerability Database\n- CWE (Common Weakness Enumeration)\n\n## Document control\n\n- Classification: {{classification}}\n- Author: {{author}}\n- Reviewer: {{reviewer}}\n- Version: {{version}}\n- Date: {{date}}\n- Project: {{project}}"
        }
      }
    }),
    P.makeTemplate({
      id: "quick-pt",
      name: "Relatório rápido de vulnerabilidades",
      locale: "pt-BR",
      preset: "forest",
      coverStyle: "GRADIENT_HERO",
      findingLayout: "ELEVATED_CARD",
      severityScale: "cvss",
      enabled: quick,
      required: ["COVER", "EXECUTIVE_SUMMARY", "FINDINGS"],
      contentPolicy: {
        includeEvidence: true,
        includeHttpRequest: false,
        includeHttpResponse: false,
        includeTocBookmarks: false,
        findingFields: ["DESCRIPTION", "IMPACT", "REMEDIATION", "EVIDENCE"]
      },
      fields: {
        companyName: "Segurança Exemplo",
        documentTitle: "Relatório de Vulnerabilidades",
        project: "Projeto Exemplo",
        component: "app.exemplo",
        environment: "Homologação",
        method: "Caixa cinza",
        team: "Time do produto",
        requester: "Patrocinador do projeto",
        owner: "Dono do sistema",
        author: "A. Analista",
        reviewer: "Revisor",
        classification: "Confidencial",
        assessmentPeriod: "outubro de 2026"
      },
      sections: {
        EXECUTIVE_SUMMARY: {
          title: "Sumário executivo",
          content: "Este relatório reúne as vulnerabilidades validadas em **{{component}}**, a pedido de {{requester}}, no ambiente **{{environment}}**, durante {{assessment_period}}.\n\nHá **{{finding_count}}** achados: {{finding_types}}. O texto é curto de propósito: cada item diz o que foi visto, o impacto e o que corrigir. A nota técnica está na classificação de risco; a prioridade de negócio fica com {{owner}}.\n\nO teste foi feito por {{author}}, da equipe {{team}}."
        },
        ASSESSMENT_SCOPE: {
          title: "Escopo",
          content: "## Dentro do escopo\n\n- Alvo: {{component}}\n- Ambiente: {{environment}}\n- Período: {{assessment_period}}\n\n## Fora do escopo\n\n- Negação de serviço\n- Engenharia social\n- Sistemas de terceiros que não pertençam a {{owner}}\n\nO trabalho foi autorizado por {{requester}} e limitado aos alvos acima."
        },
        METHODOLOGY: {
          title: "Metodologia",
          content: "Avaliação **{{method}}**, com reprodução manual antes de registrar o achado.\n\n1. Mapeamento do alvo\n2. Testes de entrada, acesso e configuração\n3. Registro de evidência e recomendação\n\nA metodologia completa fica desligada neste modelo rápido. Ative esta seção se o leitor precisar do detalhe."
        },
        REMEDIATION: {
          title: "Remediação",
          content: "## Ordem sugerida\n\n1. Corrigir o que é crítico ou alto antes da próxima entrega.\n2. Incluir os médios no ciclo atual.\n3. Tratar baixos e informativos como hardening.\n\nDepois das correções em **{{environment}}**, solicite um reteste. O modo de reteste do ICARUS marca a evidência como corrigida ou não corrigida.\n\nClassificação: {{classification}}. Autor: {{author}}. Revisor: {{reviewer}}."
        },
        APPENDIX: {
          title: "Apêndice",
          content: "## Referências\n\n- OWASP Top 10\n- CWE\n- NIST National Vulnerability Database\n\nVersão {{version}}. Data: {{date}}. Projeto: {{project}}."
        }
      }
    })
  ];
});
