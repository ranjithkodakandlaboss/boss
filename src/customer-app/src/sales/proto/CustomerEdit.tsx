// @ts-nocheck
import { useNavigate, useParams } from 'react-router-dom';
import { useStore } from './runtime';
import CustomerForm from './CustomerForm';

export default function CustomerEdit() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { customers, updateCustomer } = useStore();
  const customer = customers.find(c => c.id === id);
  if (!customer) return <div className="p-8 text-center text-sm text-[var(--text-2)]">Customer not found.</div>;
  return <CustomerForm customer={customer} title={`Edit Customer · ${customer.tradingName}`} onCancel={() => navigate(`/sales/customers/${customer.id}`)} onSave={async patch => { await updateCustomer(customer.id, patch); navigate(`/sales/customers/${customer.id}`); }} />;
}
