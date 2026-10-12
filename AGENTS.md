# Kestra Browser Use Plugin

## What

- Provides `io.kestra.plugin.browseruse.Run`, a task that runs a Browser Use cloud agent and waits for its result.

## Why

- What user problem does this solve? Flows need a cloud browser agent for sites that have no API and cannot be scripted ahead of time, without each flow reimplementing the create, poll, and cancel loop.
- Why would a team adopt this plugin in a workflow? `Run` waits for the agent, returns the answer and the cost, and cancels the remote run when the execution is killed or times out.
- What operational/business outcome does it enable? Price and availability checks, and other unscripted browser work, stay in the same flow as the tasks that use the answer, and a stopped execution does not keep spending Browser Use credits.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `browseruse`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.browseruse.Run`

### Project Structure

```
plugin-browseruse/
├── src/main/java/io/kestra/plugin/browseruse/
├── src/test/java/io/kestra/plugin/browseruse/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
