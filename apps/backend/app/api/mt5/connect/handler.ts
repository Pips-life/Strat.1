export async function createMt5Account(token: string, input: { login: string; password: string; server: string; broker?: string }) {
  const response = await fetch("https://mt-provisioning-api-v1.agiliumtrade.agiliumtrade.ai/users/current/accounts", {
    method: "POST",
    headers: { accept: "application/json", "content-type": "application/json", "auth-token": token, "transaction-id": crypto.randomUUID().replaceAll("-", "") },
    body: JSON.stringify({ login: input.login, password: input.password, name: `Strat.1 MT5 ${input.login}`, server: input.server, platform: "mt5", magic: 1001, type: "cloud-g2", keywords: input.broker ? [input.broker] : undefined, manualTrades: true }),
    cache: "no-store",
  });
  return response;
}
