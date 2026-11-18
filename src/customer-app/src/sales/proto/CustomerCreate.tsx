// @ts-nocheck
import { useNavigate } from 'react-router-dom';
import { useStore } from './runtime';
import type { Party } from './types';
import CustomerForm from './CustomerForm';

export default function CustomerCreate() {
  const navigate = useNavigate();
  const { addCustomer, customers } = useStore();
  const initial: Party = { id: `cust-new-${Date.now()}`, partyType: 'Customer', legalName: '', tradingName: '', panNumber: '', gstin: '', state: '', address: '', paymentTerms: 'Net 30', creditLimit: 0, currency: 'INR', status: 'active', contactPerson: '', phone: '', email: '', city: '', since: new Date().getFullYear().toString() };
  const existingLegalNames = customers.map(c => c.legalName);
  const existingGstins = customers.map(c => c.gstin).filter(Boolean);
  return <CustomerForm customer={initial} title="Add New Customer" primaryLabel="Save Customer" existingLegalNames={existingLegalNames} existingGstins={existingGstins} onCancel={() => navigate('/sales?tab=customers')} onSave={async patch => { const saved = await addCustomer({ ...initial, ...patch }); if (saved) navigate(`/sales/customers/${saved.id}`); }} />;
}
