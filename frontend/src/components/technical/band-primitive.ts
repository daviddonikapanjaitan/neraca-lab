// A Lightweight Charts series primitive that shades horizontal price bands (support / resistance zones) and boxes
// (a base: a price range between two dates) behind the candles.
import type {
  IChartApi,
  IPrimitivePaneRenderer,
  IPrimitivePaneView,
  ISeriesApi,
  ISeriesPrimitive,
  PrimitivePaneViewZOrder,
  SeriesAttachedParameter,
  SeriesType,
  Time,
} from "lightweight-charts"

export interface PriceBand {
  low: number
  high: number
  fill: string
  border: string
  label?: string
}

export interface PriceBox extends PriceBand {
  /** chart times (seconds) of the first and last bar */
  from: number
  to: number
}

interface CanvasTarget {
  useBitmapCoordinateSpace<T>(
    f: (scope: {
      context: CanvasRenderingContext2D
      bitmapSize: { width: number; height: number }
      horizontalPixelRatio: number
      verticalPixelRatio: number
    }) => T
  ): T
}

class BandRenderer implements IPrimitivePaneRenderer {
  constructor(private readonly source: BandPrimitive) {}

  draw() {}

  drawBackground(target: CanvasTarget) {
    const series = this.source.series
    const chart = this.source.chart
    if (!series || !chart) return
    target.useBitmapCoordinateSpace(({ context, bitmapSize, horizontalPixelRatio, verticalPixelRatio }) => {
      const font = `${Math.round(10 * verticalPixelRatio)}px ui-sans-serif, system-ui, sans-serif`
      for (const band of this.source.bands) {
        const top = series.priceToCoordinate(band.high)
        const bottom = series.priceToCoordinate(band.low)
        if (top === null || bottom === null) continue
        const y = Math.round(Math.min(top, bottom) * verticalPixelRatio)
        const h = Math.max(1, Math.round(Math.abs(bottom - top) * verticalPixelRatio))
        context.fillStyle = band.fill
        context.fillRect(0, y, bitmapSize.width, h)
        context.fillStyle = band.border
        context.fillRect(0, y, bitmapSize.width, Math.max(1, Math.round(verticalPixelRatio)))
        context.fillRect(0, y + h - Math.max(1, Math.round(verticalPixelRatio)), bitmapSize.width, Math.max(1, Math.round(verticalPixelRatio)))
        if (band.label) {
          context.font = font
          context.fillText(band.label, Math.round(6 * horizontalPixelRatio), y + Math.round(11 * verticalPixelRatio))
        }
      }
      const timeScale = chart.timeScale()
      const width = bitmapSize.width / horizontalPixelRatio
      for (const box of this.source.boxes) {
        const top = series.priceToCoordinate(box.high)
        const bottom = series.priceToCoordinate(box.low)
        if (top === null || bottom === null) continue
        const range = timeScale.getVisibleRange()
        let x1 = timeScale.timeToCoordinate(box.from as Time)
        let x2 = timeScale.timeToCoordinate(box.to as Time)
        // a box starting before the visible range starts at the left edge, one ending after it at the right edge
        if (x1 === null) x1 = (range && box.from < (range.from as number) ? 0 : null) as typeof x1
        if (x2 === null) x2 = (range && box.to > (range.to as number) ? width : null) as typeof x2
        if (x1 === null || x2 === null) continue
        const x = Math.round(x1 * horizontalPixelRatio)
        const w = Math.max(1, Math.round((x2 - x1) * horizontalPixelRatio))
        const y = Math.round(Math.min(top, bottom) * verticalPixelRatio)
        const h = Math.max(1, Math.round(Math.abs(bottom - top) * verticalPixelRatio))
        context.fillStyle = box.fill
        context.fillRect(x, y, w, h)
        context.strokeStyle = box.border
        context.lineWidth = Math.max(1, Math.round(horizontalPixelRatio))
        context.setLineDash([Math.round(4 * horizontalPixelRatio), Math.round(3 * horizontalPixelRatio)])
        context.strokeRect(x, y, w, h)
        context.setLineDash([])
        if (box.label) {
          context.font = font
          context.fillStyle = box.border
          context.fillText(box.label, x + Math.round(4 * horizontalPixelRatio), y + Math.round(11 * verticalPixelRatio))
        }
      }
    })
  }
}

class BandView implements IPrimitivePaneView {
  private readonly rendererInstance: BandRenderer

  constructor(source: BandPrimitive) {
    this.rendererInstance = new BandRenderer(source)
  }

  zOrder(): PrimitivePaneViewZOrder {
    return "bottom"
  }

  renderer() {
    return this.rendererInstance
  }
}

export class BandPrimitive implements ISeriesPrimitive<Time> {
  series: ISeriesApi<SeriesType, Time> | null = null
  chart: IChartApi | null = null
  private readonly views: readonly IPrimitivePaneView[]
  private requestUpdate: (() => void) | null = null

  constructor(
    public bands: PriceBand[],
    public boxes: PriceBox[]
  ) {
    this.views = [new BandView(this)]
  }

  attached(param: SeriesAttachedParameter<Time>) {
    this.series = param.series
    this.chart = param.chart as IChartApi
    this.requestUpdate = param.requestUpdate
  }

  detached() {
    this.series = null
    this.chart = null
    this.requestUpdate = null
  }

  paneViews() {
    return this.views
  }

  update(bands: PriceBand[], boxes: PriceBox[]) {
    this.bands = bands
    this.boxes = boxes
    this.requestUpdate?.()
  }
}
