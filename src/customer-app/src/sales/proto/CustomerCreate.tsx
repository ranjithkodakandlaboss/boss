// @ts-nocheck
import { useNavigate } from 'react-router-dom';
import { useStore } from './runtime';
import type { Party } from './types';
import CustomerForm from './CustomerForm';

export default function CustomerCreate() {
  const navigate = useNavigate();
  const { addCustomer } = useStore();
  const initial: Party = { id: `cust-new-${Date.now()}`, partyType: 'Customer', legalName: '', tradingName: '', panNumber: '', gstin: '', state: '', paymentTerms: 'Net 30', creditLimit: 0, currency: 'INR', status: 'active', contactPerson: '', phone: '', email: '', city: '', since: '2026' };
  return <CustomerForm customer={initial} title="Add New Customer" onCancel={() => navigate('/sales?tab=customers')} onSave={async patch => { const saved = await addCustomer({ ...initial, ...patch }); if (saved) navigate(`/sales/customers/${saved.id}`); }} />;
}
