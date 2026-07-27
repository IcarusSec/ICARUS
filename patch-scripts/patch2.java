    private void routeFindings(List<Finding> findings) {
        List<Finding> newOrUpdated = processDeduplication(findings, false);

        if (!newOrUpdated.isEmpty() && config.getBool("ui.show_popups", true)) {
            // Need to get the actual records to show the counts
            List<FindingRecord> recordsToShow = newOrUpdated.stream()
                .map(f -> activeFindings.get(f.similarityHash()))
                .toList();
            SwingUtilities.invokeLater(() -> showFindingsDialog(recordsToShow));
        }
    }
