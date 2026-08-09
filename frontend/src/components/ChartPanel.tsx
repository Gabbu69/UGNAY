import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

export function ChartPanel({ option, label }: { option: EChartsCoreOption; label: string }) {
  const hostRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!hostRef.current) return
    const chart = echarts.init(hostRef.current, undefined, { renderer: 'canvas' })
    chart.setOption(option)
    const observer = new ResizeObserver(() => chart.resize())
    observer.observe(hostRef.current)
    return () => {
      observer.disconnect()
      chart.dispose()
    }
  }, [option])

  return <div ref={hostRef} className="chart-panel" role="img" aria-label={label} />
}
