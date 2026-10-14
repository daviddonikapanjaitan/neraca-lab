import type { Metadata } from "next"

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"

export const metadata: Metadata = {
  title: "Credits",
}

/** Third-party notices the licenses ask to show to users. */
export default function Page() {
  return (
    <div className="flex flex-1 flex-col gap-4 p-4 pt-0">
      <Card>
        <CardHeader>
          <CardTitle>Credits</CardTitle>
          <CardDescription>Open-source software shown in the app, with the notices their licenses require.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-6 text-sm">
          <section className="space-y-1">
            <h2 className="font-medium">TradingView Lightweight Charts™</h2>
            <p>The candlestick charts of Companies › Technicals (Apache License 2.0).</p>
            <p className="font-mono text-xs">TradingView Lightweight Charts™</p>
            <p className="font-mono text-xs">Copyright (с) 2025 TradingView, Inc. https://www.tradingview.com/</p>
            <p>
              <a href="https://www.tradingview.com/" target="_blank" rel="noopener noreferrer" className="underline underline-offset-4">
                www.tradingview.com
              </a>
            </p>
          </section>
          <section className="space-y-1">
            <h2 className="font-medium">shadcn-fintech</h2>
            <p>The user interface template (MIT License), see THIRD_PARTY_LICENSE_shadcn-fintech.txt.</p>
          </section>
          <section className="space-y-1">
            <h2 className="font-medium">ta4j</h2>
            <p>The standard technical indicators computed by the backend (MIT License).</p>
          </section>
        </CardContent>
      </Card>
    </div>
  )
}
