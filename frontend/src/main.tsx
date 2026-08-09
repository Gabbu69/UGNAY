import '@fontsource/newsreader/400.css'
import '@fontsource/newsreader/600.css'
import '@fontsource/newsreader/700.css'
import '@fontsource/manrope/400.css'
import '@fontsource/manrope/500.css'
import '@fontsource/manrope/600.css'
import '@fontsource/manrope/700.css'
import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/500.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import * as Tooltip from '@radix-ui/react-tooltip'
import App from './App'
import './index.css'
import './responsive.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <Tooltip.Provider delayDuration={250}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </Tooltip.Provider>
    </QueryClientProvider>
  </StrictMode>,
)
