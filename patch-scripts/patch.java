    private void routeFindingsPassive(List<Finding> findings) {
        processDeduplication(findings, true);
    }

    private void routeFindings(List<Finding> findings) {
        List<Finding> newOrUpdated = processDeduplication(findings, false);

        // Show interactive pop-up for active scan findings if there are any and configured to do so
        if (!newOrUpdated.isEmpty() && config.getBool("ui.show_popups", true)) {
            SwingUtilities.invokeLater(() -> showFindingsDialog(newOrUpdated));
        }
    }

    private List<Finding> processDeduplication(List<Finding> findings, boolean passive) {
        List<Finding> actionable = new ArrayList<>();

        for (var finding : findings) {
            String hash = finding.similarityHash();
            var record = activeFindings.get(hash);

            if (record != null) {
                if (record.isSuppressed()) {
                    continue;
                }
                record.incrementCount();
                logAudit("Duplicate finding incremented to " + record.getCount() + "x: " + hash);
                notifyListenersOfUpdate();
            } else {
                var newRecord = new FindingRecord(finding);
                activeFindings.put(hash, newRecord);

                logAudit("New finding identified: " + hash);
                actionable.add(finding);

                if (config.getBool("pv.create_audit_issues", true) && finding.evidence() != null) {
                    try {
                        createAuditIssue(finding);
                    } catch (Exception e) {
                        api.logging().logToError("Failed to create audit issue: " + e.getMessage());
                    }
                }
                notifyListenersOfUpdate();
            }
        }
        return actionable;
    }

    public List<FindingRecord> getAllFindingRecords() {
        return new ArrayList<>(activeFindings.values());
    }

    public List<FindingRecord> getPassiveFindings() {
        return activeFindings.values().stream()
                .filter(r -> !r.isSuppressed() && r.getFinding().evidence() == null)
                .toList();
    }

    public void clearPassiveFindings() {
        activeFindings.entrySet().removeIf(e -> e.getValue().getFinding().evidence() == null);
        notifyListenersOfUpdate();
        logAudit("User cleared passive findings.");
    }
