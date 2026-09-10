package be.ehealth.hub.simulator.util;

import org.hl7.fhir.r4.model.OperationOutcome;

/**
 * Builders for the {@code OperationOutcome} responses defined by the Interhub error crosswalk
 * (transactions.md §4.2). Every non-2xx answer of the two transactions carries one.
 */
public final class Outcomes {

    private Outcomes() {
    }

    public static OperationOutcome error(OperationOutcome.IssueType code, String diagnostics) {
        return issue(OperationOutcome.IssueSeverity.ERROR, code, diagnostics, null);
    }

    public static OperationOutcome error(OperationOutcome.IssueType code, String diagnostics, String detailsText) {
        return issue(OperationOutcome.IssueSeverity.ERROR, code, diagnostics, detailsText);
    }

    private static OperationOutcome issue(OperationOutcome.IssueSeverity severity, OperationOutcome.IssueType code,
                                          String diagnostics, String detailsText) {
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue()
                .setSeverity(severity)
                .setCode(code)
                .setDiagnostics(diagnostics);
        issue.getDetails().setText(detailsText != null ? detailsText : diagnostics);
        return outcome;
    }
}
