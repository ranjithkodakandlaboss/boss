export function isRecordOverdue(status: string, dueDate: string, outstandingAmount: number): boolean {
  if (outstandingAmount <= 0) return false;
  if (["draft", "paid", "void", "cancelled", "rejected"].includes(status)) return false;
  if (status === "overdue") return true;
  if (!dueDate) return false;
  const due = new Date(dueDate.slice(0, 10)).getTime();
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return !Number.isNaN(due) && due < today.getTime();
}
