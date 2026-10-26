import { createApp } from 'vue'
import App from './App.vue'
import { createAppRouter } from './router/index.js'
import { createSession } from './session/session.js'
import { SESSION_KEY } from './session/useSession.js'
import './styles/base.css'

const session = createSession()
const router = createAppRouter({ session })

createApp(App).provide(SESSION_KEY, session).use(router).mount('#app')
