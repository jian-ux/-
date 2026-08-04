import axios from 'axios'
import { ElMessage } from 'element-plus'
import { localizedErrorText } from '../utils/displayText.js'

const request = axios.create({ baseURL: '/api', timeout: 15000 })

function redirectToLogin() {
  localStorage.removeItem('token')
  if (window.location.hash !== '#/login') window.location.href = '/#/login'
}

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})

request.interceptors.response.use(
  res => {
    const body = res.data
    if (body && typeof body.code === 'number' && body.code >= 400) {
      if (body.code === 401) redirectToLogin()
      const message = localizedErrorText(body.msg)
      ElMessage.error(message)
      return Promise.reject(new Error(message))
    }
    return body
  },
  err => {
    if (err.response?.status === 401) redirectToLogin()
    ElMessage.error(localizedErrorText(err.response?.data?.msg))
    return Promise.reject(err)
  }
)

export default request
