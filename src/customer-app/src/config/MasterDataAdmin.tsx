import { useSearchParams } from "react-router-dom";
import Tabs from "../components/shared/Tabs";
import ItemMaster from "../inventory/ItemMaster";
import BomPage from "../inventory/BomPage";
import ConfigOptionsAdmin from "./ConfigOptionsAdmin";
import CostingConfigAdmin from "./CostingConfigAdmin";

const sections = [
  { id: "reference-data", label: "Dropdown & Reference Data" },
  { id: "items", label: "Product & Material Master" },
  { id: "bom", label: "Bill of Materials" },
  { id: "costing", label: "Costing & Pricing" },
];

export default function MasterDataAdmin() {
  const [params, setParams] = useSearchParams();
  const requested = params.get("section");
  const active = sections.some((section) => section.id === requested) ? requested! : "reference-data";

  function changeSection(section: string) {
    const next = new URLSearchParams(params);
    next.set("section", section);
    setParams(next);
  }

  return (
    <div>
      <Tabs tabs={sections} active={active} onChange={changeSection} />
      {active === "reference-data" && <ConfigOptionsAdmin />}
      {active === "items" && <ItemMaster embedded />}
      {active === "bom" && <BomPage embedded />}
      {active === "costing" && <CostingConfigAdmin />}
    </div>
  );
}
