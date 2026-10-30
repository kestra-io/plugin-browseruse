# Browser Use

Run a [Browser Use](https://browser-use.com) cloud agent from a Kestra flow. Describe the work in plain language, for example "open this page, find the price, and reply with JSON". The `Run` task waits for the agent and returns the answer for the next tasks.

Browser Use is the right fit when the site has no API and the steps are hard to script ahead of time. For scripted browser checks, use the Playwright plugin.

## Setup

Create an API key at [cloud.browser-use.com/settings](https://cloud.browser-use.com/settings?tab=api-keys) and store it as a Kestra secret, for example `BROWSER_USE_API_KEY`.

## What the task does

`io.kestra.plugin.browseruse.Run` calls API v4 at `https://api.browser-use.com/api/v4`:

1. `POST /runs` starts the agent. The `prompt` property is sent as the API `task`.
2. `GET /runs/{id}/status` is polled until the run is `completed`, `failed`, or `cancelled`.
3. `GET /runs/{id}` reads the answer, the error, the token counts, and the cost once the run is terminal.

A `failed` or `cancelled` run fails the task with the API error. Token counts are recorded as task metrics. The cost is returned on `totalCostUsd`.

This task returns the agent's `result` text. Ask for JSON in the prompt, then parse `{{ outputs.your_task.result }}` with `fromJson`.

## Stop a run

A client timeout does not cancel the agent, and a running agent keeps spending credits. The task calls `POST /runs/{id}/cancel` when the task is killed or times out, and also when the task fails after the run has been created. When the worker shuts down, the run is left alone so it can finish during the grace period. If the worker then interrupts the task, that thread sends the cancel before the worker exits. A 503 from cancel is retried twice, and `Retry-After` is read as a number of seconds.

`429` responses are retried and `Retry-After` is honored. Status and full-run reads also retry HTTP 5xx and network errors. `POST /runs` is not retried after a network error or a 5xx, because that call may already have created a billed run.

## Example

```yaml
id: competitor_price_check
namespace: company.team

tasks:
  - id: check_price
    type: io.kestra.plugin.browseruse.Run
    apiKey: "{{ secret('BROWSER_USE_API_KEY') }}"
    prompt: >
      Open https://example.com/pricing, find the monthly price of the Pro plan
      and reply with only JSON like {"plan": "Pro", "price_eur": 49}.
    maxCostUsd: 1

  - id: log_price
    type: io.kestra.plugin.core.log.Log
    message: "Pro plan costs {{ fromJson(outputs.check_price.result).price_eur }} EUR"
```
