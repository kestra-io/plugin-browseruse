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

A `failed` or `cancelled` run fails the task with the API error. Token counts and cost are also recorded as task metrics.

API v4 returns `result` as text and does not accept an output schema. Ask for JSON in the prompt, then parse `{{ outputs.your_task.result }}` with `fromJson`.

## Stop a run

A client timeout does not cancel the agent, and a running agent keeps spending credits. When the Kestra execution is killed or times out, the task calls `POST /runs/{id}/cancel`.

`429` responses are retried and `Retry-After` is honored. The create call is not retried after a network error, because the run may already exist and a second call would start another billed run.

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
