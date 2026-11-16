-- V055: Seed common HSN codes for Indian manufacturing MSMEs
-- hsn_codes has NO tenant_id — it is a platform reference table (no RLS).

INSERT INTO hsn_codes (code, description, gst_rate) VALUES
  -- Chapter 72: Iron and steel
  ('7201', 'Pig iron and spiegeleisen in pigs, blocks or other primary forms', 18.00),
  ('7204', 'Ferrous waste and scrap; remelting scrap ingots of iron or steel', 18.00),
  ('7206', 'Iron and non-alloy steel in ingots or other primary forms', 18.00),
  ('7207', 'Semi-finished products of iron or non-alloy steel', 18.00),
  ('7208', 'Flat-rolled products of iron or non-alloy steel, hot-rolled', 18.00),
  ('7209', 'Flat-rolled products of iron or non-alloy steel, cold-rolled', 18.00),
  ('7210', 'Flat-rolled products of iron or non-alloy steel, plated or coated with zinc', 18.00),
  ('7213', 'Bars and rods, hot-rolled, in irregularly wound coils', 18.00),
  ('7214', 'Other bars and rods of iron or non-alloy steel', 18.00),
  ('7216', 'Angles, shapes and sections of iron or non-alloy steel', 18.00),
  ('7217', 'Wire of iron or non-alloy steel', 18.00),
  ('7220', 'Flat-rolled products of stainless steel', 18.00),
  ('7222', 'Other bars and rods of stainless steel', 18.00),
  ('7225', 'Flat-rolled products of other alloy steel', 18.00),
  ('7228', 'Other bars and rods of other alloy steel', 18.00),

  -- Chapter 73: Articles of iron or steel
  ('7301', 'Sheet piling of iron or steel; welded angles, shapes and sections', 18.00),
  ('7304', 'Tubes, pipes and hollow profiles, seamless, of iron or steel', 18.00),
  ('7305', 'Other tubes and pipes of iron or steel', 18.00),
  ('7307', 'Tube or pipe fittings of iron or steel', 18.00),
  ('7308', 'Structures and parts of structures, of iron or steel', 18.00),
  ('7312', 'Stranded wire, ropes, cables of iron or steel', 18.00),
  ('7318', 'Screws, bolts, nuts, coach screws of iron or steel', 18.00),
  ('7320', 'Springs and leaves for springs, of iron or steel', 18.00),
  ('7326', 'Other articles of iron or steel', 18.00),

  -- Chapter 74: Copper
  ('7403', 'Refined copper and copper alloys, unwrought', 18.00),
  ('7407', 'Copper bars, rods and profiles', 18.00),
  ('7408', 'Copper wire', 18.00),
  ('7409', 'Copper plates, sheets and strip', 18.00),

  -- Chapter 76: Aluminium
  ('7601', 'Unwrought aluminium', 18.00),
  ('7604', 'Aluminium bars, rods and profiles', 18.00),
  ('7605', 'Aluminium wire', 18.00),
  ('7606', 'Aluminium plates, sheets, strip and foil', 18.00),
  ('7610', 'Aluminium structures and parts of structures', 18.00),

  -- Chapter 84: Machinery
  ('8407', 'Spark-ignition reciprocating or rotary internal combustion piston engines', 28.00),
  ('8408', 'Compression-ignition internal combustion piston engines (diesel)', 28.00),
  ('8409', 'Parts for use with engines of heading 8407 or 8408', 18.00),
  ('8413', 'Pumps for liquids', 18.00),
  ('8414', 'Air or vacuum pumps, air or other gas compressors and fans', 18.00),
  ('8421', 'Centrifuges, including centrifugal dryers; filtering machinery', 18.00),
  ('8431', 'Parts for machinery of headings 8425 to 8430', 18.00),
  ('8456', 'Machine tools working by laser, ultrasonic, etc.', 18.00),
  ('8457', 'Machining centres for working metal', 18.00),
  ('8458', 'Lathes (including turning centres) for removing metal', 18.00),
  ('8459', 'Machine tools for drilling, boring, milling metal', 18.00),
  ('8460', 'Machine tools for deburring, sharpening, grinding metal', 18.00),
  ('8462', 'Machine tools for forging, hammering or die-stamping', 18.00),
  ('8466', 'Parts and accessories for machines of headings 8456 to 8465', 18.00),
  ('8467', 'Hand tools, pneumatic, hydraulic or with self-contained electric motor', 18.00),
  ('8477', 'Machinery for working rubber or plastics', 18.00),
  ('8479', 'Machines and mechanical appliances having individual functions', 18.00),
  ('8481', 'Taps, cocks, valves and similar appliances for pipes', 18.00),
  ('8482', 'Ball or roller bearings', 18.00),
  ('8483', 'Transmission shafts and cranks; bearing housings; gears', 18.00),
  ('8484', 'Gaskets and similar joints of metal sheeting', 18.00),

  -- Chapter 85: Electrical machinery
  ('8501', 'Electric motors and generators', 18.00),
  ('8503', 'Parts for electric motors or generators', 18.00),
  ('8504', 'Electrical transformers, static converters and inductors', 18.00),
  ('8511', 'Electrical ignition or starting equipment', 18.00),
  ('8534', 'Printed circuits', 18.00),
  ('8536', 'Electrical apparatus for switching or protecting electrical circuits', 18.00),
  ('8537', 'Boards, panels, consoles, cabinets for electric control', 18.00),
  ('8544', 'Insulated wire, cable and other insulated electric conductors', 18.00),

  -- Chapter 87: Vehicles and auto parts
  ('8701', 'Tractors', 12.00),
  ('8703', 'Motor cars and other motor vehicles for transport of persons', 28.00),
  ('8707', 'Bodies for motor vehicles', 28.00),
  ('8708', 'Parts and accessories of motor vehicles', 28.00),
  ('8714', 'Parts and accessories for motorcycles/cycles', 18.00),

  -- Chapter 90: Optical, measuring instruments
  ('9013', 'Liquid crystal devices, lasers, other optical appliances', 18.00),
  ('9026', 'Instruments for measuring or checking flow, level, pressure', 18.00),
  ('9031', 'Measuring or checking instruments not specified elsewhere', 18.00),
  ('9032', 'Automatic regulating or controlling instruments', 18.00)
ON CONFLICT (code) DO NOTHING;
