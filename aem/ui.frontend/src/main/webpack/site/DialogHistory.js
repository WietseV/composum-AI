import {findSingleElement} from './common.js';

/** A class that helps to manage the dialog history.
 * The dialog should have buttons with the following classes:
 * - composum-ai-reset-history-button : resets the history
 * - composum-ai-back-button : goes back in the history
 * - composum-ai-forward-button : goes forward in the history
 * We will enable/disable the buttons depending on the history state.
 */
class DialogHistory {

    /**
     * @param dialog the dialog where we have to register for the actions
     * @param getDialogStatus a function that returns the current dialog status
     * @param setDialogStatus a function that sets the current dialog status
     * @param historyList a list of history entries from a previous session on the same page/component/field. Will be modified with new entries.
     */
    constructor(dialog, getDialogStatus, setDialogStatus, historyList) {
        this.dialog = dialog;
        this.getDialogStatus = getDialogStatus;
        this.setDialogStatus = setDialogStatus;
        this.historyList = historyList;
        this.historyIndex = -1;
        this.$backButton = findSingleElement(dialog, '.composum-ai-back-button');
        this.$forwardButton = findSingleElement(dialog, '.composum-ai-forward-button');
        this.$resetButton = findSingleElement(dialog, '.composum-ai-reset-history-button');
        this.updateButtons();
        this.registerActions();
    }

    registerActions() {
        this.$backButton.click(() => this.back());
        this.$forwardButton.click(() => this.forward());
        this.$resetButton.click(() => this.resetHistory());
    }

    updateButtons() {
        this.$backButton.prop('disabled', this.historyIndex <= 0);
        this.$forwardButton.prop('disabled', this.historyIndex >= this.historyList.length - 1);
        this.$resetButton.prop('disabled', this.historyIndex < 0);
    }

    resetHistory() {
        this.historyList = [];
        this.historyIndex = -1;
        this.setDialogStatus({}); // clears dialog status
    }

    back() {
        if (this.historyIndex > 0) {
            this.maybeSaveToHistory(true);
            this.historyIndex--;
            this.setDialogStatus(this.historyList[this.historyIndex]);
            this.updateButtons();
        }
    }

    forward() {
        if (this.historyIndex < this.historyList.length - 1) {
            this.maybeSaveToHistory(true);
            this.historyIndex++;
            this.setDialogStatus(this.historyList[this.historyIndex]);
            this.updateButtons();
        }
    }

    /** If the dialog status was changed wrt. the last restored or saved state, we create a new history entry at the end of the list. */
    maybeSaveToHistory(noModifyIndex) {
        const status = this.getDialogStatus();
        var saveNeeded = false;
        if (this.historyIndex >= 0) {
            // compare with last entry
            const lastStatus = this.historyList[this.historyIndex];
            saveNeeded = !_.isEqual(status, lastStatus);
            // compare with entry at historyIndex
            if (!saveNeeded && this.historyIndex >= 0 && this.historyIndex < this.historyList.length) {
                const lastRestoredStatus = this.historyList[this.historyIndex + 1];
                saveNeeded = !_.isEqual(status, lastRestoredStatus);
            }
        }
        if (saveNeeded) {
            this.historyList.push(status);
            if (!noModifyIndex) {
                this.historyIndex = this.historyList.length - 1;
            }
            this.updateButtons();
        }
    }

}

export {DialogHistory};
