import http from '@/api/axios'

const path = '/stats/dead-letters'

export default {
  list(before) {
    return http.get(path, { params: { before } })
  },
  replay(id) {
    return http.post(path + '/replay', null, { params: { id } })
  }
}
