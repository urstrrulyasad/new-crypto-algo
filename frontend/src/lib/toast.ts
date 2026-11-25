import { createContext, useContext } from 'react'

export type ToastTone = 'success' | 'error' | 'warn' | 'info'

export interface ToastInput {
  title: string
  detail?: string
  tone?: ToastTone
}

export interface ToastContextValue {
  pushToast: (item: ToastInput) => void
}

export const ToastContext = createContext<ToastContextValue>({
  pushToast: () => {},
})

export function useToast() {
  return useContext(ToastContext)
}
