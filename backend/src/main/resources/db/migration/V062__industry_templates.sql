-- V062: Industry template options (issue #91)
-- Platform-level seed rows for the six approved MSME manufacturing industry types.
-- Applied to a new tenant at onboarding by IndustryTemplateService.
--
-- Approved types (see AGENT_OWNERSHIP_AND_PARALLEL_PLAN.md):
--   FABRICATION_STEEL, PRECISION_MACHINING, ELECTRONICS_ASSEMBLY,
--   FORGING, CASTING, GENERAL_MANUFACTURING

CREATE TABLE industry_template_options (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  industry_type TEXT NOT NULL
                  CHECK (industry_type IN (
                    'FABRICATION_STEEL','PRECISION_MACHINING','ELECTRONICS_ASSEMBLY',
                    'FORGING','CASTING','GENERAL_MANUFACTURING')),
  category_code TEXT NOT NULL,
  code          TEXT NOT NULL,
  label         TEXT NOT NULL,
  metadata      JSONB,
  sort_order    INTEGER NOT NULL DEFAULT 0,
  UNIQUE (industry_type, category_code, code)
);

CREATE INDEX idx_industry_tmpl_type ON industry_template_options(industry_type, category_code);

-- ============================================================
-- Industry: FABRICATION_STEEL
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  -- Item categories
  ('FABRICATION_STEEL','ITEM_CATEGORY','HR_PLATE',       'HR Steel Plate / Sheet',          1),
  ('FABRICATION_STEEL','ITEM_CATEGORY','MS_STRUCTURAL',  'MS Structural Sections',          2),
  ('FABRICATION_STEEL','ITEM_CATEGORY','SS_SHEET',       'Stainless Steel Sheet / Strip',   3),
  ('FABRICATION_STEEL','ITEM_CATEGORY','GI_SHEET',       'Galvanised / GI Sheet',           4),
  ('FABRICATION_STEEL','ITEM_CATEGORY','FINISHED_FAB',   'Finished Fabrications',           5),
  -- Production stages
  ('FABRICATION_STEEL','PRODUCTION_STAGES','CUTTING',       'Cutting / Shearing',              1),
  ('FABRICATION_STEEL','PRODUCTION_STAGES','BENDING',       'Bending / Rolling',               2),
  ('FABRICATION_STEEL','PRODUCTION_STAGES','FIT_UP',        'Fit-Up & Tacking',                3),
  ('FABRICATION_STEEL','PRODUCTION_STAGES','WELDING',       'MIG / TIG Welding',               4),
  ('FABRICATION_STEEL','PRODUCTION_STAGES','GRINDING',      'Grinding & Finishing',            5),
  ('FABRICATION_STEEL','PRODUCTION_STAGES','SURFACE_TREAT', 'Surface Treatment / Painting',    6),
  ('FABRICATION_STEEL','PRODUCTION_STAGES','FINAL_INSP',    'Final Inspection',                7),
  -- Inspection types
  ('FABRICATION_STEEL','INSPECTION_TYPES','INCOMING',    'Incoming Material Inspection', 1),
  ('FABRICATION_STEEL','INSPECTION_TYPES','DIM_CHECK',   'Dimensional Check',            2),
  ('FABRICATION_STEEL','INSPECTION_TYPES','WELD_VISUAL', 'Weld Visual Inspection',       3),
  ('FABRICATION_STEEL','INSPECTION_TYPES','WELD_NDT',    'Weld NDT / UT',                4),
  ('FABRICATION_STEEL','INSPECTION_TYPES','FINAL',       'Final Inspection',             5),
  -- Rejection reasons
  ('FABRICATION_STEEL','REJECTION_REASONS','DIM_OOT',    'Dimensional Out-of-Tolerance', 1),
  ('FABRICATION_STEEL','REJECTION_REASONS','WELD_DEFECT','Weld Defect',                  2),
  ('FABRICATION_STEEL','REJECTION_REASONS','DISTORTION', 'Distortion / Warping',         3),
  ('FABRICATION_STEEL','REJECTION_REASONS','SURFACE',    'Surface Defect',               4),
  ('FABRICATION_STEEL','REJECTION_REASONS','CRACK',      'Crack / Fracture',             5);

-- ============================================================
-- Industry: PRECISION_MACHINING
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('PRECISION_MACHINING','ITEM_CATEGORY','BAR_STOCK',    'Bar Stock / Billets',          1),
  ('PRECISION_MACHINING','ITEM_CATEGORY','CASTING_RM',   'Casting Blanks',               2),
  ('PRECISION_MACHINING','ITEM_CATEGORY','FORGING_RM',   'Forging Blanks',               3),
  ('PRECISION_MACHINING','ITEM_CATEGORY','CNC_COMPONENT','CNC Machined Components',       4),
  ('PRECISION_MACHINING','PRODUCTION_STAGES','SAW_CUT',  'Sawing / Parting Off',         1),
  ('PRECISION_MACHINING','PRODUCTION_STAGES','TURNING',  'Turning (Lathe)',              2),
  ('PRECISION_MACHINING','PRODUCTION_STAGES','MILLING',  'Milling (VMC / HMC)',          3),
  ('PRECISION_MACHINING','PRODUCTION_STAGES','DRILLING', 'Drilling / Boring',            4),
  ('PRECISION_MACHINING','PRODUCTION_STAGES','GRINDING', 'Grinding',                     5),
  ('PRECISION_MACHINING','PRODUCTION_STAGES','DEBURR',   'Deburring & Cleaning',         6),
  ('PRECISION_MACHINING','INSPECTION_TYPES','DIMENSIONAL','Dimensional Inspection',       1),
  ('PRECISION_MACHINING','INSPECTION_TYPES','CMM',        'CMM Check',                   2),
  ('PRECISION_MACHINING','INSPECTION_TYPES','ROUGHNESS',  'Surface Roughness',           3),
  ('PRECISION_MACHINING','INSPECTION_TYPES','HARDNESS',   'Hardness Test',               4),
  ('PRECISION_MACHINING','INSPECTION_TYPES','FINAL',      'Final Inspection',            5),
  ('PRECISION_MACHINING','REJECTION_REASONS','OVERSIZE',  'Oversize',                    1),
  ('PRECISION_MACHINING','REJECTION_REASONS','UNDERSIZE', 'Undersize',                   2),
  ('PRECISION_MACHINING','REJECTION_REASONS','RUNOUT',    'Runout / Concentricity',      3),
  ('PRECISION_MACHINING','REJECTION_REASONS','TOOLMARK',  'Tool Marks / Chatter',        4),
  ('PRECISION_MACHINING','REJECTION_REASONS','THREAD_FAIL','Thread Failure',             5);

-- ============================================================
-- Industry: ELECTRONICS_ASSEMBLY
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('ELECTRONICS_ASSEMBLY','ITEM_CATEGORY','PCB',         'PCBs (Bare)',                   1),
  ('ELECTRONICS_ASSEMBLY','ITEM_CATEGORY','COMPONENTS',  'Electronic Components',         2),
  ('ELECTRONICS_ASSEMBLY','ITEM_CATEGORY','CABLE',       'Cables & Harnesses',            3),
  ('ELECTRONICS_ASSEMBLY','ITEM_CATEGORY','ENCLOSURE',   'Enclosures / Cabinets',         4),
  ('ELECTRONICS_ASSEMBLY','ITEM_CATEGORY','FINISHED',    'Finished Assemblies',           5),
  ('ELECTRONICS_ASSEMBLY','PRODUCTION_STAGES','SMT',        'SMT Placement',              1),
  ('ELECTRONICS_ASSEMBLY','PRODUCTION_STAGES','REFLOW',      'Reflow Soldering',          2),
  ('ELECTRONICS_ASSEMBLY','PRODUCTION_STAGES','THT',         'Through-Hole Assembly',     3),
  ('ELECTRONICS_ASSEMBLY','PRODUCTION_STAGES','WAVE',        'Wave Soldering',            4),
  ('ELECTRONICS_ASSEMBLY','PRODUCTION_STAGES','AOI',         'AOI Inspection',            5),
  ('ELECTRONICS_ASSEMBLY','PRODUCTION_STAGES','FUNC_TEST',   'Functional Testing',        6),
  ('ELECTRONICS_ASSEMBLY','PRODUCTION_STAGES','BOX_BUILD',   'Box Build / Final Assembly',7),
  ('ELECTRONICS_ASSEMBLY','INSPECTION_TYPES','IQC',      'Incoming Quality Control',      1),
  ('ELECTRONICS_ASSEMBLY','INSPECTION_TYPES','AOI_INSP', 'AOI Inspection',               2),
  ('ELECTRONICS_ASSEMBLY','INSPECTION_TYPES','ICT',      'In-Circuit Test',              3),
  ('ELECTRONICS_ASSEMBLY','INSPECTION_TYPES','FUNC_TEST','Functional Test',              4),
  ('ELECTRONICS_ASSEMBLY','INSPECTION_TYPES','HIPOT',    'Hi-Pot Test',                  5),
  ('ELECTRONICS_ASSEMBLY','REJECTION_REASONS','SOLDER_BRIDGE','Solder Bridge',           1),
  ('ELECTRONICS_ASSEMBLY','REJECTION_REASONS','OPEN_JOINT',  'Open Joint',              2),
  ('ELECTRONICS_ASSEMBLY','REJECTION_REASONS','WRONG_COMP',  'Wrong Component',         3),
  ('ELECTRONICS_ASSEMBLY','REJECTION_REASONS','MISSING_COMP','Missing Component',       4),
  ('ELECTRONICS_ASSEMBLY','REJECTION_REASONS','FUNC_FAIL',   'Functional Failure',      5);

-- ============================================================
-- Industry: FORGING
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('FORGING','ITEM_CATEGORY','BILLET',       'Billets / Ingots',                  1),
  ('FORGING','ITEM_CATEGORY','FORGING_BLANK','Forging Blanks',                    2),
  ('FORGING','ITEM_CATEGORY','ROUGH_FORGING','Rough Forgings',                    3),
  ('FORGING','ITEM_CATEGORY','HT_FORGING',   'Heat-Treated Forgings',             4),
  ('FORGING','ITEM_CATEGORY','FINISHED_FRG', 'Finished / Machined Forgings',      5),
  ('FORGING','PRODUCTION_STAGES','BILLET_CUT',  'Billet Cutting',                 1),
  ('FORGING','PRODUCTION_STAGES','HEATING',     'Heating & Soaking',              2),
  ('FORGING','PRODUCTION_STAGES','FORGING',     'Forging (Press / Hammer)',        3),
  ('FORGING','PRODUCTION_STAGES','TRIMMING',    'Trimming & Piercing',            4),
  ('FORGING','PRODUCTION_STAGES','SHOT_BLAST',  'Shot Blasting',                  5),
  ('FORGING','PRODUCTION_STAGES','HEAT_TREAT',  'Heat Treatment',                 6),
  ('FORGING','PRODUCTION_STAGES','FINAL_INSP',  'Final Inspection',               7),
  ('FORGING','INSPECTION_TYPES','DIMENSIONAL',  'Dimensional Inspection',          1),
  ('FORGING','INSPECTION_TYPES','HARDNESS',     'Hardness Test',                   2),
  ('FORGING','INSPECTION_TYPES','UT',           'Ultrasonic Flaw Detection',       3),
  ('FORGING','INSPECTION_TYPES','MPI',          'Magnetic Particle Inspection',    4),
  ('FORGING','INSPECTION_TYPES','FINAL',        'Final Inspection',                5),
  ('FORGING','REJECTION_REASONS','LAPS',        'Laps / Folds',                    1),
  ('FORGING','REJECTION_REASONS','UNDERFILL',   'Underfill / Unfill',              2),
  ('FORGING','REJECTION_REASONS','CRACK',       'Cracks / Voids',                  3),
  ('FORGING','REJECTION_REASONS','DIM_OOT',     'Dimensional Out-of-Tolerance',    4),
  ('FORGING','REJECTION_REASONS','SCALE',       'Scale / Oxide Inclusions',        5);

-- ============================================================
-- Industry: CASTING
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('CASTING','ITEM_CATEGORY','SCRAP',        'Scrap / Returns',                   1),
  ('CASTING','ITEM_CATEGORY','ALLOY_INGOT',  'Alloy Ingots / Master Alloys',      2),
  ('CASTING','ITEM_CATEGORY','SAND',         'Sand & Moulding Materials',         3),
  ('CASTING','ITEM_CATEGORY','ROUGH_CASTING','Rough Castings',                    4),
  ('CASTING','ITEM_CATEGORY','FINISHED_CST', 'Machined / Finished Castings',      5),
  ('CASTING','PRODUCTION_STAGES','PATTERN',     'Pattern Preparation',            1),
  ('CASTING','PRODUCTION_STAGES','MOULD',       'Mould Making / Core Setting',    2),
  ('CASTING','PRODUCTION_STAGES','MELTING',     'Melting & Alloying',             3),
  ('CASTING','PRODUCTION_STAGES','POURING',     'Pouring',                        4),
  ('CASTING','PRODUCTION_STAGES','KNOCKOUT',    'Knockout & Fettling',            5),
  ('CASTING','PRODUCTION_STAGES','HEAT_TREAT',  'Heat Treatment',                 6),
  ('CASTING','PRODUCTION_STAGES','MACHINING',   'Machining & Dressing',           7),
  ('CASTING','INSPECTION_TYPES','VISUAL',    'Visual Inspection',                 1),
  ('CASTING','INSPECTION_TYPES','DIMENSIONAL','Dimensional Check',                2),
  ('CASTING','INSPECTION_TYPES','ULTRA',     'Ultrasonic / Radiography',          3),
  ('CASTING','INSPECTION_TYPES','HARDNESS',  'Hardness Test',                     4),
  ('CASTING','INSPECTION_TYPES','FINAL',     'Final Inspection',                  5),
  ('CASTING','REJECTION_REASONS','POROSITY', 'Porosity',                          1),
  ('CASTING','REJECTION_REASONS','BLOW_HOLE','Blow Holes',                        2),
  ('CASTING','REJECTION_REASONS','COLD_SHUT','Cold Shut',                         3),
  ('CASTING','REJECTION_REASONS','SHRINKAGE','Shrinkage Cavity',                  4),
  ('CASTING','REJECTION_REASONS','DIM_OOT',  'Dimensional Out-of-Tolerance',      5),
  ('CASTING','REJECTION_REASONS','INCLUSION','Inclusions / Sand Holes',           6);

-- ============================================================
-- Industry: GENERAL_MANUFACTURING
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('GENERAL_MANUFACTURING','ITEM_CATEGORY','RAW_MATERIAL','Raw Material',          1),
  ('GENERAL_MANUFACTURING','ITEM_CATEGORY','SUB_COMPONENT','Sub-Components',       2),
  ('GENERAL_MANUFACTURING','ITEM_CATEGORY','WIP',          'Work-in-Progress',     3),
  ('GENERAL_MANUFACTURING','ITEM_CATEGORY','FINISHED',     'Finished Goods',       4),
  ('GENERAL_MANUFACTURING','ITEM_CATEGORY','CONSUMABLE',   'Consumables',          5),
  ('GENERAL_MANUFACTURING','ITEM_CATEGORY','PACKAGING',    'Packaging Material',   6),
  ('GENERAL_MANUFACTURING','PRODUCTION_STAGES','RECEIVING',    'Receiving & Inspection',   1),
  ('GENERAL_MANUFACTURING','PRODUCTION_STAGES','PRE_PROCESS',  'Pre-processing',           2),
  ('GENERAL_MANUFACTURING','PRODUCTION_STAGES','PRIMARY',      'Primary Process',          3),
  ('GENERAL_MANUFACTURING','PRODUCTION_STAGES','SUB_ASSEMBLY', 'Sub-assembly',             4),
  ('GENERAL_MANUFACTURING','PRODUCTION_STAGES','FINAL_ASSEMBLY','Final Assembly',          5),
  ('GENERAL_MANUFACTURING','PRODUCTION_STAGES','TESTING',      'Testing & QC',             6),
  ('GENERAL_MANUFACTURING','PRODUCTION_STAGES','PACKING',      'Packing & Dispatch',       7),
  ('GENERAL_MANUFACTURING','INSPECTION_TYPES','INCOMING',  'Incoming Quality Check',       1),
  ('GENERAL_MANUFACTURING','INSPECTION_TYPES','IN_PROCESS','In-Process Inspection',        2),
  ('GENERAL_MANUFACTURING','INSPECTION_TYPES','FINAL',     'Final Inspection',             3),
  ('GENERAL_MANUFACTURING','INSPECTION_TYPES','PRE_DISP',  'Pre-Dispatch Inspection',      4),
  ('GENERAL_MANUFACTURING','REJECTION_REASONS','DIMENSIONAL','Dimensional Failure',        1),
  ('GENERAL_MANUFACTURING','REJECTION_REASONS','SURFACE',    'Surface / Visual Defect',    2),
  ('GENERAL_MANUFACTURING','REJECTION_REASONS','FUNCTIONAL', 'Functional Failure',         3),
  ('GENERAL_MANUFACTURING','REJECTION_REASONS','MATERIAL',   'Wrong / Substandard Material',4),
  ('GENERAL_MANUFACTURING','REJECTION_REASONS','PACKAGING',  'Packaging Defect',           5);
