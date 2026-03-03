package forge.net.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores per-game metrics extracted from log/output analysis.
 * Adapted for master's protocol (no delta sync — focuses on errors and completion).
 */
public class GameLogMetrics {

    /**
     * Classification of how a game failed (or NONE if it succeeded).
     */
    public enum FailureMode {
        NONE,              // Game completed successfully
        TIMEOUT,           // Game exceeded time limit
        EXCEPTION,         // Exception/error logged
        INCOMPLETE         // Game didn't complete for unknown reason
    }

    // Failure tracking
    private FailureMode failureMode = FailureMode.NONE;
    private int firstErrorTurn = -1;

    // Game identification
    private String logFileName;
    private int gameIndex = -1;
    private int playerCount = 2;

    // Game completion status
    private boolean gameCompleted;
    private int turnCount;
    private String winner;

    // Error tracking
    private List<String> warnings = new ArrayList<>();
    private List<String> errors = new ArrayList<>();
    private Map<String, Integer> errorCounts = new HashMap<>();
    private int sendErrors;
    private Map<String, NetworkLogAnalyzer.ErrorContext> errorContexts = new LinkedHashMap<>();

    /**
     * Check if this game was successful (completed with no send errors).
     * Log-detected errors (e.g. caught exceptions) are tracked but do not affect success.
     */
    public boolean isSuccessful() {
        return gameCompleted && sendErrors == 0;
    }

    // Getters and setters

    public FailureMode getFailureMode() { return failureMode; }
    public void setFailureMode(FailureMode failureMode) { this.failureMode = failureMode; }

    public int getFirstErrorTurn() { return firstErrorTurn; }
    public void setFirstErrorTurn(int firstErrorTurn) { this.firstErrorTurn = firstErrorTurn; }

    public String getLogFileName() { return logFileName; }
    public void setLogFileName(String logFileName) { this.logFileName = logFileName; }

    public int getGameIndex() { return gameIndex; }
    public void setGameIndex(int gameIndex) { this.gameIndex = gameIndex; }

    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }

    public boolean isGameCompleted() { return gameCompleted; }
    public void setGameCompleted(boolean gameCompleted) { this.gameCompleted = gameCompleted; }

    public int getTurnCount() { return turnCount; }
    public void setTurnCount(int turnCount) { this.turnCount = turnCount; }

    public String getWinner() { return winner; }
    public void setWinner(String winner) { this.winner = winner; }

    public int getSendErrors() { return sendErrors; }
    public void setSendErrors(int sendErrors) { this.sendErrors = sendErrors; }

    public List<String> getWarnings() { return warnings; }

    public void addWarning(String warning) {
        if (warnings.size() < 100) {
            warnings.add(warning);
        }
    }

    public List<String> getErrors() { return errors; }

    public void addError(String error) {
        if (errors.size() < 100) {
            errors.add(error);
        }
    }

    /** Increment the occurrence count for a normalized error pattern. No cap. */
    public void incrementErrorCount(String normalizedError) {
        errorCounts.merge(normalizedError, 1, Integer::sum);
    }

    /** Get occurrence counts by normalized error pattern. */
    public Map<String, Integer> getErrorCounts() { return errorCounts; }

    /** Get error contexts keyed by normalized error pattern. */
    public Map<String, NetworkLogAnalyzer.ErrorContext> getErrorContexts() { return errorContexts; }
    public void setErrorContexts(Map<String, NetworkLogAnalyzer.ErrorContext> errorContexts) {
        this.errorContexts = errorContexts;
    }

    /** Get the first error context (convenience for backward compatibility). */
    public NetworkLogAnalyzer.ErrorContext getErrorContext() {
        return errorContexts.isEmpty() ? null : errorContexts.values().iterator().next();
    }

    @Override
    public String toString() {
        return String.format(
                "GameLogMetrics[file=%s, players=%d, completed=%b, turns=%d, winner=%s, " +
                "sendErrors=%d, warnings=%d, errors=%d, failureMode=%s]",
                logFileName, playerCount, gameCompleted, turnCount, winner,
                sendErrors, warnings.size(), errors.size(), failureMode);
    }
}
