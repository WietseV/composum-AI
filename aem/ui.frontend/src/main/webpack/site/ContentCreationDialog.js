/** Implementation for the actions of the Content Creation Dialog - button actions, drop down list actions etc. */

import {AICreate} from './AICreate.js';

const APPROXIMATE_MARKDOWN_SERVLET = '/bin/cpm/ai/approximated.markdown.md';

/**
 * Represents the Content Creation Dialog.
 */

class ContentCreationDialog {

    /**
     * Creates a new ContentCreationDialog.
     *
     * @param {Object} options - The options for the dialog.
     * @param {HTMLElement} options.dialog - The dialog element.
     * @param {string} options.componentPath - The path of the edited component.
     * @param {string} options.oldContent - The current content of the field.
     * @param {Function} options.writebackCallback - A function that takes the new content and writes it back to the field.
     * @param {boolean} options.isrichtext - True if the field is a richtext field, false if it's a plain text field.
     * @param {boolean} options.stackeddialog - True if the dialog is stacked and we have to close the dialog ourselves without generating events to not disturb the underlying dialog.
     * @param {Function} [options.onFinishCallback] - A function that is called when the dialog is closed.
     */
    constructor({dialog, componentPath, oldContent, writebackCallback, isrichtext, stackeddialog, onFinishCallback}) {
        console.log("ContentCreationDialog constructor ", arguments);
        this.componentPath = componentPath;
        this.dialog = $(dialog);
        this.oldContent = oldContent;
        this.writebackCallback = writebackCallback;
        this.onFinishCallback = onFinishCallback;
        this.isrichtext = isrichtext;
        this.stackeddialog = stackeddialog;
        this.removeFormAction();
        this.assignElements();
        this.bindActions();
        this.setSourceContentArea(oldContent);
        this.createServlet = new AICreate(this.streamingCallback.bind(this), this.doneCallback.bind(this), this.errorCallback.bind(this));
        this.showError();
        this.setLoading(false);
        this.fullscreen();
    }

    fullscreen() {
        this.dialog.find('form').addClass(' _coral-Dialog--fullscreenTakeover');
        this.dialog.find('coral-dialog-footer').children().appendTo(this.dialog.find('coral-dialog-header div.cq-dialog-actions'));
    }

    removeFormAction() {
        // we handle the submit ourselves.
        let form = this.dialog.find('form');
        form.removeAttr('action');
        form.removeAttr('method');
    }

    findSingleElement(selector) {
        const $el = this.dialog.find(selector);
        if ($el.length !== 1) {
            console.error('BUG! ContentCreationDialog: missing element for selector', selector, $el, $el.length);
        }
        return $el;
    }

    assignElements() {
        this.$promptArea = this.findSingleElement('.composum-ai-prompt-textarea');
        this.$predefinedPromptsSelector = this.findSingleElement('.composum-ai-predefined-prompts');
        this.$contentSelector = this.findSingleElement('.composum-ai-content-selector');
        this.$sourceContentArea = this.findSingleElement('.composum-ai-source-content');
        this.$textLengthSelector = this.findSingleElement('.composum-ai-text-length-selector');
        this.$responseArea = this.findSingleElement('.composum-ai-response-field');
    }

    bindActions() {
        this.$predefinedPromptsSelector.on('change', this.onPredefinedPromptsChanged.bind(this));
        this.$promptArea.on('change', this.onPromptAreaChanged.bind(this));
        this.$contentSelector.on('change', this.onContentSelectorChanged.bind(this));
        this.$sourceContentArea.on('change', this.onSourceContentAreaChanged.bind(this));
        this.findSingleElement('.composum-ai-generate-button').on('click', this.onGenerateButtonClicked.bind(this));
        this.findSingleElement('.composum-ai-stop-button').on('click', function () {
            this.createServlet.abortRunningCalls();
            this.setLoading(false);
        }.bind(this));
        this.findSingleElement('.composum-ai-reset-button').on('click', function () {
            this.$promptArea.val('');
            this.setSourceContentArea(this.oldContent);
            this.setResponseArea('');
            this.onPredefinedPromptsChanged();
            this.onContentSelectorChanged();
        }.bind(this));
        this.findSingleElement('.cq-dialog-submit').on('click', this.onSubmit.bind(this));
        this.findSingleElement('.cq-dialog-cancel').on('click', this.onCancel.bind(this));
    }

    onPredefinedPromptsChanged(event) {
        console.log("onPredefinedPromptsChanged", arguments);
        const prompt = this.$predefinedPromptsSelector.val();
        if (prompt !== '-') {
            this.$promptArea.val(prompt);
        }
    }

    onPromptAreaChanged(event) {
        console.log("onPromptAreaChanged", arguments);
        this.$predefinedPromptsSelector.val('-');
    }

    onContentSelectorChanged(event) {
        console.log("onContentSelectorChanged", arguments);
        // possible values widget, component, page, lastoutput, -
        const key = this.$contentSelector.val();
        switch (key) {
            case 'lastoutput':
                this.setSourceContentArea(this.getResponseArea());
                break;
            case 'widget':
                this.setSourceContentArea(this.oldContent);
                break;
            case 'component':
                this.retrieveValue(this.componentPath, (value) => this.setSourceContentArea(value));
                break;
            case 'page':
                this.retrieveValue(this.pagePath(this.componentPath), (value) => this.setSourceContentArea(value));
                break;
            case '-':
                break;
            default:
                console.error('BUG! ContentCreationDialog: unknown content selector value', key);
        }
    }

    setSourceContentArea(value) {
        this.$sourceContentArea.val(value);
    }

    setResponseArea(value) {
        this.$responseArea.val(value);
    }

    getResponseArea() {
        return this.$responseArea.val();
    }

    onSourceContentAreaChanged(event) {
        console.log("onSourceContentAreaChanged", arguments);
        this.$contentSelector.val('-');
    }

    retrieveValue(path, callback) {
        $.ajax({
            url: Granite.HTTP.externalize(APPROXIMATE_MARKDOWN_SERVLET + path),
            type: "GET",
            dataType: "text",
            success: function (data) {
                callback(data);
            }.bind(this),
            error: function (xhr, status, error) {
                console.log("error loading approximate markdown", xhr, status, error);
            }
        });

        // http://localhost:4502/bin/cpm/ai/approximated.markdown.md/content/wknd/us/en/magazine/_jcr_content
        // http://localhost:4502/bin/cpm/ai/approximated.markdown/content/wknd/language-masters/composum-ai-testpages/jcr:content?_=1693499009746
    }

    /** The path until the /jcr:content */
    pagePath(path) {
        if (path.lastIndexOf('/jcr:content') > 0) {
            return path.substring(0, path.lastIndexOf('/jcr:content') + '/jcr:content'.length);
        } else if (path.lastIndexOf('_jcr_content') > 0) {
            return path.substring(0, path.lastIndexOf('_jcr_content') + '_jcr_content'.length);
        } else {
            return path;
        }
    }

    onGenerateButtonClicked(event) {
        console.log("onGenerateButtonClicked", arguments);
        this.showError(undefined);
        const data = {
            prompt: this.$promptArea.val(),
            source: this.$sourceContentArea.val(),
            textLength: this.$textLengthSelector.val()
        };
        console.log("createContent", data);
        this.setLoading(true);
        this.createServlet.createContent(data);
    }

    streamingCallback(data) {
        console.log("ContentCreationDialog streamingCallback", arguments);
        this.setResponseArea(data);
    }

    doneCallback(data) {
        console.log("ContentCreationDialog doneCallback", arguments);
        if (data && data.data && data.data.result && data.data.result.finishreason === 'STOP') {
            this.showError('The generated content stopped because of the length restriction.');
        } else {
            this.showError(undefined);
        }
        this.setLoading(false);
    }

    errorCallback(data) {
        console.log("ContentCreationDialog errorCallback", arguments);
        this.showError(data);
        this.setLoading(false);
    }

    setLoading(loading) {
        if (loading) {
            this.findSingleElement('.composum-ai-generate-button').attr('disabled', 'disabled');
            this.findSingleElement('.composum-ai-loading').show();
        } else {
            this.findSingleElement('.composum-ai-generate-button').removeAttr('disabled');
            this.findSingleElement('.composum-ai-loading').hide();
        }
    }

    /** Shows the error text if error is given, hides it if it's falsy. */
    showError(error) {
        if (!error) {
            this.findSingleElement('.composum-ai-error-columns').hide();
        } else {
            console.error("ContentCreationDialog showError", arguments);
            this.findSingleElement('.composum-ai-alert').text(error);
            this.findSingleElement('.composum-ai-error-columns').show();
        }
    }

    onSubmit(event) {
        console.log("ContentCreationDialog onSubmit", arguments);
        const response = this.getResponseArea();
        this.closeDialog(event);
        // only after closing since dialog is now out of the way
        if (typeof this.writebackCallback == 'function') {
            this.writebackCallback(response);
        }
    }

    onCancel(event) {
        console.log("ContentCreationDialog onCancel", arguments);
        this.closeDialog(event);
    }

    closeDialog(event) {
        if (this.stackeddialog) {
            // unfortunately otherwise the dialog closes the other dialog which we have been called from, too.
            event.preventDefault();
            event.stopPropagation();
            console.log('removing dialog', this.dialog[0]);
            this.dialog[0].remove();
        }
        // else: let the dialog close itself.
        if (typeof this.onFinishCallback == 'function') {
            this.onFinishCallback();
        }
    }

}

export {ContentCreationDialog};

console.log("ContentCreationDialog.js loaded", ContentCreationDialog);
