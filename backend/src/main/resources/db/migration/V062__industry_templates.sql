-- V062: Industry template options (issue #91)
-- Platform-level seed rows for the six supported MSME manufacturing industry types.
-- Applied to a new tenant at onboarding by IndustryTemplateService.

CREATE TABLE industry_template_options (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  industry_type TEXT NOT NULL
                  CHECK (industry_type IN (
                    'auto_components','sheet_metal','injection_moulding',
                    'precision_machining','electronic_assemblies','textiles')),
  category_code TEXT NOT NULL,
  code          TEXT NOT NULL,
  label         TEXT NOT NULL,
  metadata      JSONB,
  sort_order    INTEGER NOT NULL DEFAULT 0,
  UNIQUE (industry_type, category_code, code)
);

CREATE INDEX idx_industry_tmpl_type ON industry_template_options(industry_type, category_code);

-- ============================================================
-- Industry: auto_components (ACMA-aligned)
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  -- Item categories
  ('auto_components','ITEM_CATEGORY','CASTING',      'Castings',                 1),
  ('auto_components','ITEM_CATEGORY','FORGING',      'Forgings',                 2),
  ('auto_components','ITEM_CATEGORY','SHEET_METAL',  'Sheet Metal Parts',        3),
  ('auto_components','ITEM_CATEGORY','RUBBER_PARTS', 'Rubber & Sealing Parts',   4),
  ('auto_components','ITEM_CATEGORY','FASTENERS',    'Fasteners & Hardware',     5),
  ('auto_components','ITEM_CATEGORY','FINISHED_ASSY','Finished Assemblies',      6),
  -- Production stages
  ('auto_components','PRODUCTION_STAGES','BLANKING',      'Blanking / Shearing',       1),
  ('auto_components','PRODUCTION_STAGES','FORMING',       'Forming / Bending',         2),
  ('auto_components','PRODUCTION_STAGES','MACHINING',     'Machining (CNC/VMC)',        3),
  ('auto_components','PRODUCTION_STAGES','HEAT_TREAT',    'Heat Treatment',            4),
  ('auto_components','PRODUCTION_STAGES','SURFACE_TREAT', 'Surface Treatment',         5),
  ('auto_components','PRODUCTION_STAGES','ASSEMBLY',      'Assembly',                  6),
  ('auto_components','PRODUCTION_STAGES','PDI',           'Pre-Dispatch Inspection',   7),
  -- Inspection types
  ('auto_components','INSPECTION_TYPES','INCOMING',   'Incoming Material Inspection', 1),
  ('auto_components','INSPECTION_TYPES','IN_PROCESS', 'In-Process Inspection',        2),
  ('auto_components','INSPECTION_TYPES','FINAL',      'Final Inspection',             3),
  ('auto_components','INSPECTION_TYPES','DIM_CHECK',  'Dimensional Check',            4),
  -- Rejection reasons
  ('auto_components','REJECTION_REASONS','DIMENSIONAL','Dimensional Out-of-Tolerance', 1),
  ('auto_components','REJECTION_REASONS','SURFACE',    'Surface Defect',               2),
  ('auto_components','REJECTION_REASONS','MATERIAL',   'Wrong Material / Grade',       3),
  ('auto_components','REJECTION_REASONS','BURR',       'Burrs / Sharp Edges',          4),
  ('auto_components','REJECTION_REASONS','CRACK',      'Cracks / Voids',               5);

-- ============================================================
-- Industry: sheet_metal
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('sheet_metal','ITEM_CATEGORY','HR_COIL',       'HR Steel Coil / Sheet',     1),
  ('sheet_metal','ITEM_CATEGORY','CR_COIL',       'CR Steel Coil / Sheet',     2),
  ('sheet_metal','ITEM_CATEGORY','GI_SHEET',      'Galvanised / GI Sheet',     3),
  ('sheet_metal','ITEM_CATEGORY','TUBE_SECTION',  'Tubes & Sections',          4),
  ('sheet_metal','ITEM_CATEGORY','FINISHED_PART', 'Fabricated / Welded Parts', 5),
  ('sheet_metal','PRODUCTION_STAGES','BLANKING',   'Blanking',    1),
  ('sheet_metal','PRODUCTION_STAGES','PUNCHING',   'Punching',    2),
  ('sheet_metal','PRODUCTION_STAGES','BENDING',    'Bending',     3),
  ('sheet_metal','PRODUCTION_STAGES','WELDING',    'Welding',     4),
  ('sheet_metal','PRODUCTION_STAGES','GRINDING',   'Grinding',    5),
  ('sheet_metal','PRODUCTION_STAGES','PAINTING',   'Painting',    6),
  ('sheet_metal','INSPECTION_TYPES','INCOMING',    'Incoming Inspection', 1),
  ('sheet_metal','INSPECTION_TYPES','DIM_CHECK',   'Dimensional Check',   2),
  ('sheet_metal','INSPECTION_TYPES','WELD_VISUAL', 'Weld Visual Check',   3),
  ('sheet_metal','REJECTION_REASONS','WARPING',    'Warping / Distortion', 1),
  ('sheet_metal','REJECTION_REASONS','WELD_DEFECT','Weld Defect',          2),
  ('sheet_metal','REJECTION_REASONS','PAINT_DEF',  'Paint / Coating Defect',3),
  ('sheet_metal','REJECTION_REASONS','DIMENSIONAL','Dimensional Failure',  4);

-- ============================================================
-- Industry: injection_moulding
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('injection_moulding','ITEM_CATEGORY','POLYMER',    'Polymer Granules / Raw Material', 1),
  ('injection_moulding','ITEM_CATEGORY','MASTERBATCH','Masterbatch / Pigments',          2),
  ('injection_moulding','ITEM_CATEGORY','INSERT',     'Metal Inserts',                   3),
  ('injection_moulding','ITEM_CATEGORY','MOULDED',    'Moulded Components',              4),
  ('injection_moulding','ITEM_CATEGORY','ASSEMBLY',   'Moulded Assemblies',              5),
  ('injection_moulding','PRODUCTION_STAGES','MATERIAL_PREP','Material Preparation / Drying', 1),
  ('injection_moulding','PRODUCTION_STAGES','MOULDING',     'Injection Moulding',            2),
  ('injection_moulding','PRODUCTION_STAGES','DEFLASHING',   'Deflashing / Trimming',         3),
  ('injection_moulding','PRODUCTION_STAGES','INSERT_FIT',   'Insert Fitting',                4),
  ('injection_moulding','PRODUCTION_STAGES','PAINTING',     'Painting / Finishing',          5),
  ('injection_moulding','INSPECTION_TYPES','INCOMING',      'Incoming Raw Material',         1),
  ('injection_moulding','INSPECTION_TYPES','FIRST_SHOT',    'First Shot Approval',           2),
  ('injection_moulding','INSPECTION_TYPES','PATROL',        'Patrol Inspection',             3),
  ('injection_moulding','REJECTION_REASONS','SINK_MARK',    'Sink Marks',                    1),
  ('injection_moulding','REJECTION_REASONS','WARPAGE',      'Warpage',                       2),
  ('injection_moulding','REJECTION_REASONS','FLASH',        'Flash / Flashing',              3),
  ('injection_moulding','REJECTION_REASONS','SHORT_SHOT',   'Short Shot',                    4),
  ('injection_moulding','REJECTION_REASONS','COLOUR_VAR',   'Colour Variation',              5);

-- ============================================================
-- Industry: precision_machining
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('precision_machining','ITEM_CATEGORY','BAR_STOCK',   'Bar Stock / Billets',       1),
  ('precision_machining','ITEM_CATEGORY','CASTING_RM',  'Casting Blanks',            2),
  ('precision_machining','ITEM_CATEGORY','FORGING_RM',  'Forging Blanks',            3),
  ('precision_machining','ITEM_CATEGORY','FINISHED_CNC','CNC Machined Components',   4),
  ('precision_machining','PRODUCTION_STAGES','SAW_CUT',  'Sawing / Parting Off',     1),
  ('precision_machining','PRODUCTION_STAGES','TURNING',  'Turning (Lathe)',          2),
  ('precision_machining','PRODUCTION_STAGES','MILLING',  'Milling (VMC/HMC)',        3),
  ('precision_machining','PRODUCTION_STAGES','DRILLING', 'Drilling / Boring',        4),
  ('precision_machining','PRODUCTION_STAGES','GRINDING', 'Grinding',                 5),
  ('precision_machining','PRODUCTION_STAGES','DEBURR',   'Deburring & Cleaning',     6),
  ('precision_machining','INSPECTION_TYPES','CMM',       'CMM / Dimensional',        1),
  ('precision_machining','INSPECTION_TYPES','SURFACE',   'Surface Roughness',        2),
  ('precision_machining','INSPECTION_TYPES','HARDNESS',  'Hardness Testing',         3),
  ('precision_machining','REJECTION_REASONS','OVERSIZE',  'Oversize',                1),
  ('precision_machining','REJECTION_REASONS','UNDERSIZE', 'Undersize',               2),
  ('precision_machining','REJECTION_REASONS','RUNOUT',    'Runout / Concentricity',  3),
  ('precision_machining','REJECTION_REASONS','TOOLMARK',  'Tool Marks / Chatter',    4);

-- ============================================================
-- Industry: electronic_assemblies
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('electronic_assemblies','ITEM_CATEGORY','PCB',        'PCBs (Bare)',                1),
  ('electronic_assemblies','ITEM_CATEGORY','COMPONENTS', 'Electronic Components',      2),
  ('electronic_assemblies','ITEM_CATEGORY','CABLE',      'Cables & Harnesses',         3),
  ('electronic_assemblies','ITEM_CATEGORY','ENCLOSURE',  'Enclosures / Cabinets',      4),
  ('electronic_assemblies','ITEM_CATEGORY','FINISHED',   'Finished Assemblies',        5),
  ('electronic_assemblies','PRODUCTION_STAGES','SMT',        'SMT Placement',              1),
  ('electronic_assemblies','PRODUCTION_STAGES','REFLOW',      'Reflow Soldering',           2),
  ('electronic_assemblies','PRODUCTION_STAGES','THT',         'Through-Hole Assembly',      3),
  ('electronic_assemblies','PRODUCTION_STAGES','WAVE',        'Wave Soldering',             4),
  ('electronic_assemblies','PRODUCTION_STAGES','AOI',         'AOI / Visual Inspection',    5),
  ('electronic_assemblies','PRODUCTION_STAGES','TESTING',     'Functional Testing',         6),
  ('electronic_assemblies','PRODUCTION_STAGES','BOX_BUILD',   'Box Build / Final Assembly', 7),
  ('electronic_assemblies','INSPECTION_TYPES','IQC',      'Incoming Quality Control', 1),
  ('electronic_assemblies','INSPECTION_TYPES','AOI_INSP', 'AOI Inspection',           2),
  ('electronic_assemblies','INSPECTION_TYPES','FUNC_TEST','Functional Test',           3),
  ('electronic_assemblies','INSPECTION_TYPES','HIPOT',    'Hi-Pot Test',              4),
  ('electronic_assemblies','REJECTION_REASONS','SOLDER_BRIDGE', 'Solder Bridge',      1),
  ('electronic_assemblies','REJECTION_REASONS','OPEN_JOINT',   'Open Joint',          2),
  ('electronic_assemblies','REJECTION_REASONS','WRONG_COMP',   'Wrong Component',     3),
  ('electronic_assemblies','REJECTION_REASONS','MISSING_COMP', 'Missing Component',   4),
  ('electronic_assemblies','REJECTION_REASONS','FUNC_FAIL',    'Functional Failure',  5);

-- ============================================================
-- Industry: textiles (including garments)
-- ============================================================
INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order) VALUES
  ('textiles','ITEM_CATEGORY','YARN',       'Yarn / Thread',          1),
  ('textiles','ITEM_CATEGORY','FABRIC',     'Fabric / Grey Cloth',    2),
  ('textiles','ITEM_CATEGORY','ACCESSORY',  'Accessories & Trims',    3),
  ('textiles','ITEM_CATEGORY','DYES',       'Dyes & Chemicals',       4),
  ('textiles','ITEM_CATEGORY','FINISHED',   'Finished Garments',      5),
  ('textiles','PRODUCTION_STAGES','KNITTING',  'Knitting / Weaving',    1),
  ('textiles','PRODUCTION_STAGES','DYEING',    'Dyeing & Processing',   2),
  ('textiles','PRODUCTION_STAGES','CUTTING',   'Cutting',               3),
  ('textiles','PRODUCTION_STAGES','STITCHING', 'Stitching / Sewing',    4),
  ('textiles','PRODUCTION_STAGES','FINISHING', 'Finishing',             5),
  ('textiles','PRODUCTION_STAGES','PACKING',   'Packing',               6),
  ('textiles','INSPECTION_TYPES','FABRIC_INSP','Fabric Inspection',      1),
  ('textiles','INSPECTION_TYPES','IN_LINE',    'In-Line Inspection',     2),
  ('textiles','INSPECTION_TYPES','END_LINE',   'End-Line Inspection',    3),
  ('textiles','INSPECTION_TYPES','AQL',        'AQL Final Inspection',   4),
  ('textiles','REJECTION_REASONS','HOLE',       'Holes / Cuts',          1),
  ('textiles','REJECTION_REASONS','STAIN',      'Stains',                2),
  ('textiles','REJECTION_REASONS','SKIP_STITCH','Skip Stitching',        3),
  ('textiles','REJECTION_REASONS','SIZE_FAIL',  'Measurement Failure',   4),
  ('textiles','REJECTION_REASONS','SHADE_VAR',  'Shade Variation',       5);
