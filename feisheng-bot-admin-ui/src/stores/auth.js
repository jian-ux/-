import { defineStore } from 'pinia'
import request from '../api/index.js'

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: localStorage.getItem('token') || '', userInfo: null }),
  getters: { isLoggedIn: state => !!state.token },
  actions: {
    async login(username, password) {
      const res = await request.post('/admin/login', { username, password })
      this.token = res.data.token
      this.userInfo = res.data.userInfo
      localStorage.setItem('token', res.data.token)
    },
    async fetchUserInfo() {
      const res = await request.get('/admin/user/info')
      this.userInfo = res.data
    },
    logout() { this.token = ''; this.userInfo = null; localStorage.removeItem('token') }
  }
})
