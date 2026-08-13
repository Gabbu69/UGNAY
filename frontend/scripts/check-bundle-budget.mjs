import { readFileSync, readdirSync } from 'node:fs'
import { basename, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

const DIST = new URL('../dist/', import.meta.url)
const ASSETS = new URL('../dist/assets/', import.meta.url)
const assetsPath = fileURLToPath(ASSETS)
const ENTRY_LIMIT = 148 * 1024
const html = readFileSync(new URL('index.html', DIST), 'utf8')
const entryMatch = html.match(/<script[^>]+type="module"[^>]+src="([^"]+\.js)"/)

if (!entryMatch) throw new Error('Could not identify the module entry in dist/index.html. Run npm run build first.')

const entryName = basename(entryMatch[1])
const javascript = readdirSync(assetsPath).filter((name) => name.endsWith('.js'))
const sources = new Map(javascript.map((name) => [name, readFileSync(join(assetsPath, name))]))
const entry = sources.get(entryName)
if (!entry) throw new Error(`Entry asset ${entryName} was not found in dist/assets.`)

const entryGzipBytes = gzipSync(entry).byteLength
const echartsChunks = [...sources].filter(([, source]) => /echarts/i.test(source.toString('utf8'))).map(([name]) => name)
const cytoscapeChunks = [...sources].filter(([, source]) => /cytoscape/i.test(source.toString('utf8'))).map(([name]) => name)
const failures = []

if (entryGzipBytes > ENTRY_LIMIT) failures.push(`Entry is ${(entryGzipBytes / 1024).toFixed(2)} KiB gzip; budget is 148 KiB.`)
if (echartsChunks.length) failures.push(`ECharts code remains in: ${echartsChunks.join(', ')}`)
if (cytoscapeChunks.includes(entryName)) failures.push('Cytoscape is present in the entry bundle.')
if (cytoscapeChunks.length !== 1 || !cytoscapeChunks[0].startsWith('TraceGraph-')) {
  failures.push(`Cytoscape must exist only in one lazy TraceGraph chunk; found: ${cytoscapeChunks.join(', ') || 'none'}`)
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exitCode = 1
} else {
  console.log(`Bundle budget passed: ${entryName} ${(entryGzipBytes / 1024).toFixed(2)} KiB gzip; Cytoscape isolated in ${cytoscapeChunks[0]}; ECharts absent.`)
}
