function dateKey(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function isOutsideProject(date: string, projectStart: string | null, projectDue: string | null): boolean {
  return Boolean(projectStart && date < projectStart || projectDue && date > projectDue)
}

export function isStartDateDisabled(
  date: Date,
  projectStart: string | null,
  projectDue: string | null,
  selectedDue: string | null,
): boolean {
  const value = dateKey(date)
  return isOutsideProject(value, projectStart, projectDue)
    || Boolean(selectedDue && value > selectedDue)
}

export function isEndDateDisabled(
  date: Date,
  projectStart: string | null,
  projectDue: string | null,
  selectedStart: string | null,
): boolean {
  const value = dateKey(date)
  return isOutsideProject(value, projectStart, projectDue)
    || Boolean(selectedStart && value < selectedStart)
}

export function isProjectDateDisabled(
  date: Date,
  projectStart: string | null,
  projectDue: string | null,
): boolean {
  return isOutsideProject(dateKey(date), projectStart, projectDue)
}

export function validateDateRange(
  startDate: string | null,
  dueDate: string | null,
  projectStart: string | null,
  projectDue: string | null,
): string {
  if (startDate && dueDate && dueDate < startDate) {
    return '截止日期不能早于开始日期'
  }
  if (startDate && isOutsideProject(startDate, projectStart, projectDue)
      || dueDate && isOutsideProject(dueDate, projectStart, projectDue)) {
    return `日期必须在项目周期 ${projectStart ?? '不限'} 至 ${projectDue ?? '不限'} 内`
  }
  return ''
}
