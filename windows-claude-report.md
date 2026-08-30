# ICARUS – Reporting Tab UI QA & Responsiveness Review

**Data:** 2026-08-30  
**Build:** Burp Suite Professional v2026.7.3 · Extensão ICARUS  
**Escopo:** `ICARUS ▸ Reporting` (Comportamento em telas menores, redimensionamento de janela e múltiplos monitores)  
**Ambiente Testado:** 
- Monitor 1 (Primário): 3440 x 1440 (Ultra-wide 21:9)
- Monitor 2 (Secundário / Retrato): 1080 x 1920
- Janelas redimensionadas: 1094 x 1125, 950 x 900 e larguras < 1200px

---

## 🚨 Resumo dos Problemas Críticos Encontrados

Quando a janela do Burp Suite é redimensionada para resoluções menores (como laptops, split-screen ~1080px ou monitor secundário vertical), o layout da aba **Reporting** quebra severamente:
1. **Controles essenciais são empurrados para fora da tela** (ex.: `Finding Card Layout`, `Font Family`, `Font Size`, botões de ação).
2. **Cards e grupos sofrem overflow horizontal e clipping sem scroll**, tornando opções inacessíveis.
3. **Os limites dos breakpoints estão descalibrados**, ativando o modo `REGULAR` (lado a lado) em larguras onde os elementos não cabem.

---

## 🐛 Detalhamento dos Bugs e O que Deve Ser Arrumado

### 1. Descalibração dos Breakpoints (`Breakpoint.java` / `ResponsiveContainer.java`)
- **Problema:** A classe `Breakpoint.java` define:
  ```java
  if (widthPx < 680)  return COMPACT;
  if (widthPx < 1000) return NARROW;
  if (widthPx < 1600) return REGULAR;
  return ULTRAWIDE;
  ```
  Em uma janela de ~1094px (ou split-screen), `widthPx` é avaliado como `REGULAR`. O layout `REGULAR` assume que há espaço abundante e coloca painéis em 4 colunas ou lado a lado, estourando a largura da tela.
- **Além disso:** O `ResponsiveContainer` calcula o breakpoint baseado em `getWidth()` do container externo e não na largura real útil do `content` após margens e insets.
- **O que arrumar:**
  - Ajustar os thresholds de breakpoint para valores realistas considerando a interface do Burp:
    - `COMPACT`: `< 750px`
    - `NARROW`: `< 1200px` (ou `< 1250px`)
    - `REGULAR`: `1250px` a `1700px`
    - `ULTRAWIDE`: `>= 1700px`
  - Garantir que telas de ~1000px–1150px entrem em `NARROW` (layout empilhado verticalmente).

---

### 2. "Finding Card Layout" e "Cover Page" cortados (`LayoutSectionPanel.java`)
- **Problema:** No modo `REGULAR`, `coverPageGroup` (3 thumbnails = ~500px) e `findingCardGroup` (2 thumbnails = ~340px) são dispostos lado a lado (`gridx=0`, `gridx=1`). Em janelas com largura inferior a ~1200px, o grupo `Finding Card Layout` é totalmente empurrado para fora da borda direita da janela.
- **O que arrumar:**
  - Em `LayoutSectionPanel.onBreakpointChanged()`, somente dispor lado a lado se `bp == Breakpoint.REGULAR && width >= 1250` ou `bp == Breakpoint.ULTRAWIDE`.
  - Usar `WrapLayout` ou empilhamento vertical (`gridy=0`, `gridy=1`) no modo `NARROW`/telas menores.

---

### 3. Controles de Tema esmagados e cortados em 4 colunas (`ColorsThemeSectionPanel.java`)
- **Problema:** No modo `REGULAR`, os campos `Primary Accent`, `Secondary Accent`, `Font Family` e `Font Size` são colocados em uma única linha com 4 colunas (`gridx=0, 1, 2, 3`).
  - Em larguras menores que ~1300px, o dropdown `Font Family` e o spinner `Font Size` são empurrados para fora da tela.
  - A linha de badges (`Severity Badge Colors`) estoura a lateral direita, cortando o badge `Not Fixed`.
- **O que arrumar:**
  - No modo `NARROW` ou para larguras intermediárias (< 1300px), organizar os controles em uma grade 2x2 (como já feito no NARROW) em vez de forçar 4 colunas em linha única.
  - Assegurar que `badgesRow` quebre linha suavemente com `WrapLayout` respeitando a largura do card.

---

### 4. Explosão de largura horizontal na área de Tokens e Detalhes (`DetailPane.java` / `VariableChipRow.java`)
- **Problema:** 
  - `VariableChipRow` possui 8 chips (`{{team}}`, `{{component}}`, etc.).
  - Dentro de `DetailPane`, o `chipWrapper` (BoxLayout vertical) consulta o `WrapLayout.preferredLayoutSize()`. Durante o cálculo inicial onde a largura do container ainda é indefinida, o `WrapLayout` assume `targetWidth = Integer.MAX_VALUE`, retornando uma largura preferida de ~750px (todos os 8 chips em uma única linha horizontal).
  - Isso força o `DetailPane` a ter uma largura mínima/preferida de ~750px.
  - No `SectionFlowPanel`, `listPanel` possui 360px fixos + `detailPane` 750px = **1110px mínimos** só para esse painel, estourando qualquer janela com largura inferior a 1150px.
- **O que arrumar:**
  - No `DetailPane`, permitir que `chipRow` e `topPanel` quebrem linhas em larguras menores sem propagar uma largura mínima rígida de 750px.
  - No `SectionFlowPanel`, quando em modo `NARROW` / telas menores, empilhar a lista de seções e o painel de detalhes verticalmente (lista em cima com altura fixa ~220px, painel de detalhes embaixo).

---

### 5. Toolbar / Barra de Ações com inicialização vazia (`ToolbarPanel.java`)
- **Problema:** O construtor de `ToolbarPanel` instancia o painel com `GridBagLayout`, mas **não adiciona** os componentes filhos `left` e `right` até que `onBreakpointChanged()` seja disparado pelo listener. Se o listener inicial não disparar ou se a janela for criada em dimensões compactas, a toolbar pode ficar invisível ou desformatada.
- **O que arrumar:**
  - Chamar uma renderização padrão (ex.: `onBreakpointChanged(Breakpoint.NARROW)`) diretamente no construtor de `ToolbarPanel`.
  - Permitir que os botões da direita (`Preview PDF`, `Preview HTML`, `Save Profile`) quebrem linha abaixo dos controles de perfil caso a largura seja insuficiente.

---

### 6. Política de Scroll Horizontal Desativada (`ReportingSettingsTab.java`)
- **Problema:** A aba configura o scroll com:
  ```java
  scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
  ```
  Quando os componentes internos estouram a largura útil da tela, o usuário fica sem nenhuma forma de rolar para a direita para alcançar os botões ou campos cortados.
- **O que arrumar:**
  - Mudar para `ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED` como rede de segurança para telas ultracompactas (< 800px).

---

## 📋 Checklist de Ações Recomendadas para Correção

- [ ] **`Breakpoint.java`**: Aumentar threshold de `NARROW` para `< 1200px` (para cobrir janelas normais de 1024–1150px).
- [ ] **`LayoutSectionPanel.java`**: Empilhar verticalmente `Cover Page` e `Finding Card Layout` quando em `NARROW`/telas menores.
- [ ] **`ColorsThemeSectionPanel.java`**: Usar grid 2x2 para os seletores de fontes e cores até pelo menos 1300px.
- [ ] **`DetailPane.java` / `VariableChipRow.java`**: Corrigir cálculo de tamanho preferido dos chips para permitir quebra de linha fluida sem travar largura mínima em 750px.
- [ ] **`SectionFlowPanel.java`**: Garantir transição suave para layout empilhado (lista acima, detalhes abaixo) quando a largura total for menor que 1200px.
- [ ] **`ToolbarPanel.java`**: Inicializar layout no construtor e permitir wrap dos botões de preview/save.
- [ ] **`ReportingSettingsTab.java`**: Habilitar `HORIZONTAL_SCROLLBAR_AS_NEEDED` no `JScrollPane` principal.

