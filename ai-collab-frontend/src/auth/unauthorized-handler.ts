let handler: () => void | Promise<void> = () => undefined

export function setUnauthorizedHandler(next: () => void | Promise<void>): void {
  handler = next
}

export function handleUnauthorized(): void {
  void handler()
}
