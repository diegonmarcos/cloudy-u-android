#!/usr/bin/env python3
"""Hardtech-BioMed content builder (stdlib only): writes content.json (the topics and texts every tab teaches) and assets.json (the licence
register of every file next to it) for the models in DIR. content.json is what the app reads at run time (never baked into the APK).

usage: build_content.py DIR      (DIR holds body.glb, brain.glb, dna.glb; writes DIR/content.json and DIR/assets.json)
Highlight specs: a substring of a node's name, or system:<id> (see libs:cad-core viewer.js, cadHighlight).
"""
import hashlib, json, os, sys

BP3D = ("BodyParts3D, (c) The Database Center for Life Science, CC BY-SA 2.1 Japan (lifesciencedb.jp/bp3d). Parts regrouped and "
        "simplified for this app; this derived model is shared under the same licence.")
NOTICE = ("Educational overview in neutral wording, not medical advice. The body model is a generalised reference body: individual anatomy "
          "varies, and what is shown is simplified.")

ALL_SYS = ["skeletal", "muscular", "integumentary", "nervous", "sensory", "cardiovascular", "respiratory", "digestive", "urinary", "endocrine", "immune", "reproductive"]
LAB = {"skeletal": "Skeleton", "muscular": "Muscles", "integumentary": "Skin", "nervous": "Brain and cord", "sensory": "Eyes", "cardiovascular": "Heart and vessels",
       "respiratory": "Respiratory", "digestive": "Digestive", "urinary": "Urinary", "endocrine": "Endocrine glands", "immune": "Immune organs", "reproductive": "Reproductive"}


def T(id, title, text, hl=(), isolate=False):
    return {"id": id, "title": title, "text": text, "highlight": list(hl), "isolate": isolate}


def layers(systems):
    return [{"id": s, "label": LAB[s], "match": ["system:" + s]} for s in systems]


def S(s): return "system:" + s


tabs = {}

# ------------------------------------------------------------------------------------------------------------- Anatomy
tabs["anatomy"] = {
    "model": "body", "title": "Anatomy",
    "intro": "The whole body in 3D. Turn it with one finger, zoom with a pinch, tap any part to name it. Layers show or hide a body system; a region or system chip lights its parts and dims the rest.",
    "layers": layers(["integumentary", "skeletal", "muscular", "cardiovascular", "respiratory", "digestive", "urinary", "endocrine", "nervous", "sensory", "immune", "reproductive"]),
    "hidden": [S("integumentary")],
    "groups": [
        {"id": "regions", "label": "Regions", "topics": [
            T("head", "Head and neck", "The head holds the brain inside the skull, the eyes in their orbits and the openings of the airway and the digestive tract. The neck carries the airway, the esophagus, the spinal cord inside the vertebral column, and the large vessels between the head and the chest.", ["skull", "brain", "eyes", "pituitary", "trachea", "esophagus"]),
            T("thorax", "Thorax", "The thorax is the chest: the rib cage and sternum protect the heart and the lungs, and the diaphragm forms its floor. Breathing changes the volume of the chest; the heart sits between the lungs, slightly to the left.", ["ribs", "sternum", "costal_cartilage", "heart", "lungs", "trachea", "diaphragm", "thymus", "aorta"]),
            T("abdomen", "Abdomen", "The abdomen holds most of the digestive organs (stomach, liver, gallbladder, pancreas, small and large intestine), the spleen and the kidneys. The vertebral column supports it from behind and layers of muscle close it at the front.", ["stomach", "liver", "gallbladder", "pancreas", "small_intestine", "large_intestine", "spleen", "kidneys", "adrenals"]),
            T("pelvis", "Pelvis", "The pelvis is a ring of bone formed by the two hip bones, the sacrum and the coccyx. It carries the weight of the upper body to the legs and holds the urinary bladder and the reproductive organs.", ["hip_bone", "urinary_bladder", "prostate", "testes", "seminal", "ureters"]),
            T("upper_limb", "Upper limb", "The shoulder girdle (clavicle and scapula) attaches the arm to the trunk. The humerus, the radius and the ulna and the many small bones of the hand give the arm its reach and the hand its fine movement.", ["clavicle", "scapula", "humerus", "radius", "ulna", "hand_bones"]),
            T("lower_limb", "Lower limb", "The femur, the patella, the tibia and the fibula and the bones of the foot form the leg. They carry body weight and, with the large muscles around them, drive walking, running and standing.", ["femur", "patella", "tibia", "fibula", "foot_bones", "hip_bone"]),
        ]},
        {"id": "systems", "label": "Body systems", "topics": [
            T("sys_skeletal", "Skeletal system", "The skeleton gives shape and support, protects organs, anchors muscles and makes movement possible at the joints. Bone also stores calcium and, in adults, red bone marrow in some bones makes blood cells.", [S("skeletal")], True),
            T("sys_muscular", "Muscular system", "Skeletal muscles pull on bones through tendons to produce movement and to hold posture. The model shows the muscles that the open dataset provides; some muscles are not part of it.", [S("muscular")], True),
            T("sys_cardiovascular", "Cardiovascular system", "The heart pumps blood through arteries to the tissues and back through veins; a second circuit runs through the lungs. The model shows the heart, the aorta, the venae cavae, and the main systemic and pulmonary vessels.", [S("cardiovascular")], True),
            T("sys_respiratory", "Respiratory system", "The airway (trachea and bronchi) leads air to the lungs, where oxygen enters the blood and carbon dioxide leaves it. The diaphragm is the main breathing muscle.", [S("respiratory")], True),
            T("sys_digestive", "Digestive system", "Food passes through the esophagus, stomach and intestines while enzymes and bile break it down and the body absorbs the nutrients. The liver, gallbladder and pancreas deliver secretions to the gut.", [S("digestive")], True),
            T("sys_urinary", "Urinary system", "The kidneys filter the blood and make urine, which the ureters carry to the bladder for storage until it is passed.", [S("urinary")], True),
            T("sys_nervous", "Nervous system", "The brain and the spinal cord process information and issue commands; nerves carry signals to and from the body. The model shows the brain and the spinal cord.", [S("nervous"), S("sensory")], True),
            T("sys_endocrine", "Endocrine system", "Glands release hormones into the blood to regulate growth, metabolism, stress responses and reproduction. The model shows the pituitary gland and the adrenal glands; the pancreas also acts as an endocrine organ.", [S("endocrine"), "pancreas"], True),
            T("sys_immune", "Immune organs", "The thymus and the spleen are immune organs: T cells mature in the thymus, and the spleen filters the blood and takes part in immune responses. Lymph nodes and bone marrow also belong to the system but are not in this model.", [S("immune")], True),
            T("sys_reproductive", "Reproductive system", "The reproductive organs produce gametes and hormones. The open dataset behind this model provides the male reference anatomy only, so the model shows the prostate, the testes and the seminal vesicles.", [S("reproductive")], True),
            T("sys_skin", "Skin", "The skin is the largest organ: a barrier against water loss and infection, a sensor for touch and temperature, and a regulator of body temperature. It is hidden by default so that the organs can be seen; it is available as a layer.", [S("integumentary")]),
        ]},
    ],
}

# ---------------------------------------------------------------------------------------------------------- Physiology
tabs["physiology"] = {
    "model": "body", "title": "Physiology",
    "intro": "Each body system as a working system: what it takes in, what it does and what it hands on. Pick a system to light its organs on the body.",
    "layers": layers(["skeletal", "muscular", "integumentary"]),
    "hidden": [S("muscular"), S("integumentary")],
    "groups": [{"id": "systems", "label": "Functional systems", "topics": [
        T("energy", "Energy supply", "The digestive tract breaks food down into small molecules (glucose, amino acids, fatty acids) that the small intestine absorbs. Blood carries them first to the liver, which stores, converts and releases them according to need; the pancreas adds digestive enzymes and the hormones insulin and glucagon that steer the use of glucose.\nThe large intestine reclaims water and salts, and hosts a community of microbes that takes part in digestion.", ["esophagus", "stomach", "liver", "gallbladder", "pancreas", "small_intestine", "large_intestine"], True),
        T("circulation", "Circulation", "The heart is a double pump. The right side sends blood through the lungs to pick up oxygen; the left side sends oxygenated blood through the aorta and the arteries to every tissue. Veins return the blood to the heart. Blood carries oxygen, nutrients, hormones, heat and waste, and its pressure and flow are adjusted by the nervous system and by hormones.", ["heart", "aorta", "vena_cava", "arteries", "veins", "pulm_vessels"], True),
        T("respiration", "Respiration", "Breathing moves air in and out of the lungs: the diaphragm and the muscles between the ribs enlarge the chest and air flows in; when they relax the lungs recoil. In the tiny air sacs of the lungs oxygen enters the blood and carbon dioxide leaves it. The breathing rate follows the carbon dioxide level and acidity of the blood.", ["lungs", "trachea", "diaphragm", "ribs"], True),
        T("excretion", "Cleaning and excretion", "The kidneys filter the blood, return what the body needs and excrete the rest as urine, which controls water, salt and acid-base balance and removes waste such as urea. The liver converts many substances so that they can be excreted, the lungs release carbon dioxide, the intestines remove undigested material and the skin loses water and salts as sweat.", ["kidneys", "ureters", "urinary_bladder", "liver", "large_intestine"], True),
        T("regulation", "Hormonal regulation", "Endocrine glands release hormones into the blood. The pituitary gland at the base of the brain controls several other glands; the adrenal glands above the kidneys release stress hormones; the pancreas controls blood glucose; the thymus matures immune cells. Hormone levels are held in range by feedback loops.", ["pituitary", "adrenals", "pancreas", "thymus"], True),
        T("control", "Control and sensing", "The nervous system collects information from the senses, integrates it in the brain and the spinal cord and drives muscles and glands. Fast electrical signals run along nerves; slower chemical signals are hormones. The eyes are shown as one of the sense organs.", ["brain", "spinal_cord", "eyes"], True),
        T("support", "Support and movement", "Bones form a rigid frame, muscles pull on it and joints let it move. The same frame protects the brain, the heart and the lungs, and its marrow takes part in blood formation.", [S("skeletal"), S("muscular")], True),
        T("defence", "Defence", "The skin and the mucous membranes form the first barrier. White blood cells and the organs that make and filter them (the thymus and the spleen in this model) recognise and clear microbes and damaged cells. See the Immuno tab for details.", ["thymus", "spleen", S("integumentary")], True),
        T("reproduction", "Reproduction", "The reproductive organs make gametes and sex hormones. Hormones from the pituitary gland control their activity, and the same hormone axes shape development and, in later life, the changes of ageing. The open dataset behind this model shows the male reference anatomy only.", [S("reproductive"), "pituitary"], True),
        T("thermo", "Temperature regulation", "The body holds its core temperature close to a set point. Muscles and the liver produce heat, blood flow to the skin carries it to the surface, and sweat evaporation cools it. The control centre is in the hypothalamus of the brain.", ["brain", S("integumentary"), "liver", S("muscular")]),
    ]}],
}

# ---------------------------------------------------------------------------------------------------------- BioChem
tabs["biochem"] = {
    "model": "body", "title": "BioChem",
    "intro": "The chemistry that keeps the body running: its daily rhythm, how it makes and stores energy, how it clears waste and how its hormones steer reproduction. Each topic lights the organs it centres on.",
    "layers": layers(["skeletal", "muscular", "integumentary"]),
    "hidden": [S("muscular"), S("integumentary")],
    "groups": [
        {"id": "rhythm", "label": "Rhythms", "topics": [
            T("circadian", "Circadian cycle", "Almost every cell keeps a roughly 24-hour clock made of interlocking gene and protein feedback loops. A master clock in the hypothalamus, the suprachiasmatic nucleus, is set mainly by light arriving through the eyes and keeps the other clocks in step.\nThe cycle shapes sleep and wakefulness, body temperature, blood pressure, hormone release (melatonin at night, cortisol rising before waking), digestion and the timing of liver and kidney activity. Shifted light, meals or sleep can pull the clocks out of step with one another.", ["brain", "eyes", "pituitary"]),
            T("sleep", "Sleep", "Sleep alternates between non-REM stages, which are deep and restorative for body and memory, and REM sleep, in which brain activity resembles waking. Pressure to sleep builds with time awake (adenosine accumulates) and is balanced against the circadian alerting signal.", ["brain", "pituitary", "adrenals"]),
            T("cortisol", "Stress and the cortisol rhythm", "Cortisol, released by the adrenal glands under the control of the pituitary gland, follows a daily curve: highest shortly after waking and lowest around midnight. In acute stress it mobilises glucose and adjusts the immune response; a feedback loop switches it off again.", ["adrenals", "pituitary", "brain"]),
        ]},
        {"id": "energy", "label": "Energy metabolism", "topics": [
            T("atp", "ATP and energy currency", "Cells run on adenosine triphosphate (ATP). Splitting its last phosphate releases energy that drives muscle contraction, nerve signalling and the building of molecules. A resting adult turns over roughly their own body weight in ATP each day, so it is constantly rebuilt from food.", [S("muscular"), "heart", "brain"]),
            T("glucose", "Glucose to ATP", "Glycolysis splits glucose into pyruvate in the cell fluid and yields a little ATP without oxygen. With oxygen, pyruvate enters the mitochondria, where the citric acid cycle and the respiratory chain produce most of the ATP and release carbon dioxide and water. Without enough oxygen the cell falls back on lactate formation.", ["lungs", "heart", S("muscular")]),
            T("fed_fasted", "Fed and fasted states", "After a meal, insulin from the pancreas lets cells take up glucose and the liver and muscle store it as glycogen; extra energy is stored as fat. Between meals glucagon prompts the liver to release glucose from glycogen and, later, to make new glucose and ketone bodies from fat and protein breakdown products.", ["pancreas", "liver", "stomach", "small_intestine", S("muscular")]),
            T("fat", "Fat metabolism", "Fatty acids are the largest energy store: they are released from fat tissue, carried in the blood and burned in mitochondria by beta-oxidation. The liver packages fats into lipoproteins and makes cholesterol, which is needed for membranes and for steroid hormones and bile acids.", ["liver", "gallbladder", "pancreas", "small_intestine"]),
            T("muscle_energy", "Energy in muscle", "Muscle draws on stored phosphocreatine for the first seconds of effort, on glycolysis for hard effort and on oxidative metabolism of glucose and fatty acids for sustained effort. Training raises the number of mitochondria and the capacity to use oxygen.", [S("muscular"), "heart", "lungs"]),
            T("brain_energy", "Energy for the brain", "The brain is about two percent of body weight and uses roughly a fifth of its energy, mostly as glucose. During prolonged fasting it can use ketone bodies made by the liver for a part of its needs.", ["brain", "liver"]),
        ]},
        {"id": "clean", "label": "Detox and cleaning", "topics": [
            T("liver_detox", "Liver biotransformation", "The liver changes many drugs, hormones and foreign chemicals in two steps: phase I enzymes (the cytochrome P450 family) modify them, and phase II enzymes attach groups such as glucuronic acid or glutathione so that they dissolve in water and can leave through bile or urine. Many substances are processed by only a few enzymes, which is why some drugs interact.", ["liver", "gallbladder", "portal"]),
            T("kidney_clear", "Kidney clearance", "Each kidney holds about a million filtering units. Blood is filtered, most water, salts and glucose are taken back, and waste such as urea and creatinine is left in the urine. The kidneys also hold the acid-base balance and make hormones for blood pressure and red blood cell production.", ["kidneys", "ureters", "urinary_bladder"]),
            T("nitrogen", "Nitrogen waste and the urea cycle", "Breaking down protein releases ammonia, which is toxic. The liver turns it into urea in the urea cycle; the blood carries urea to the kidneys, which excrete it.", ["liver", "kidneys"]),
            T("acid_base", "Acid-base balance", "Blood pH is held in a narrow range. Buffers in the blood act at once, the lungs adjust carbon dioxide within minutes, and the kidneys adjust acid and bicarbonate over hours to days.", ["lungs", "kidneys", "trachea"]),
            T("gut_clear", "Bile, gut and microbes", "Bile made by the liver and stored in the gallbladder carries waste to the intestine and helps absorb fats. The large intestine is home to a large community of microbes that ferment fibre, make some vitamins and take part in recycling bile acids.", ["liver", "gallbladder", "large_intestine", "small_intestine"]),
            T("water", "Water and salt balance", "Thirst, the hormone vasopressin from the pituitary gland, the adrenal hormone aldosterone and the kidneys together keep the volume and salt concentration of body fluids steady.", ["kidneys", "pituitary", "adrenals"]),
        ]},
        {"id": "repro", "label": "Reproduction", "topics": [
            T("axis", "Hormone axes", "Reproduction is steered by a chain: the hypothalamus releases GnRH in pulses, the pituitary gland answers with LH and FSH, and the gonads release sex hormones (testosterone, estrogens, progesterone) that act on the body and feed back on the brain. The same chain governs puberty and later changes with age.", ["brain", "pituitary", "testes"]),
            T("gametes", "Gametes", "Gametes are the sex cells: sperm and egg cells carry half the genetic material of the person, and meiosis shuffles it so that each is different. Sperm are made continuously in the testes; egg cells are formed before birth and mature in cycles.", ["testes", "seminal", "prostate"]),
            T("cycle", "The menstrual cycle", "In people with ovaries and a uterus, a cycle of about a month is driven by FSH, LH, estrogen and progesterone: a follicle matures, ovulation releases an egg cell and the lining of the uterus is shed if there is no pregnancy. The open dataset behind the 3D model shows male anatomy only, so these organs are not drawn.", ["pituitary", "brain"]),
            T("fertilisation", "Fertilisation and pregnancy", "When a sperm cell fuses with an egg cell the new cell divides and implants in the lining of the uterus. Placental hormones such as hCG, progesterone and estrogens maintain pregnancy, and the mother's metabolism, blood volume and heart output adapt to it.", ["seminal", "testes", "pituitary"]),
            T("repro_hormones", "Sex hormones beyond reproduction", "Sex hormones also act on bone density, muscle, fat distribution, mood and the lipid profile. They are made from cholesterol in the gonads and, in smaller amounts, in the adrenal glands.", ["testes", "adrenals", S("skeletal")]),
        ]},
    ],
}

# ---------------------------------------------------------------------------------------------------------- BioMech
joints = [
    ("shoulder", "Shoulder", "A ball-and-socket joint: the head of the humerus sits in the shallow socket of the scapula, which gives the largest range of motion of any joint at the price of stability. The rotator cuff muscles and the joint capsule hold it in place. The clavicle links the girdle to the sternum.", ["scapula", "clavicle", "humerus", "m_supraspinatus", "m_infraspinatus", "m_subscapularis", "m_teres"]),
    ("elbow", "Elbow", "A hinge between the humerus and the forearm bones, with a pivot between the radius and the ulna that turns the palm up or down (pronation and supination).", ["humerus", "radius", "ulna", "m_brachialis", "m_brachioradialis", "m_supinator", "m_anconeus"]),
    ("wrist", "Wrist and hand", "Eight small carpal bones link the radius to the hand; the hand's many joints let the fingers and the thumb grip, pinch and make fine movements, driven mostly by tendons from forearm muscles.", ["radius", "ulna", "hand_bones", "m_flexor_digitorum", "m_extensor_digitorum", "m_flexor_pollicis", "m_extensor_pollicis"]),
    ("hip", "Hip", "A ball-and-socket joint between the head of the femur and the cup of the hip bone. It is deep and stable, carries the weight of the upper body and moves the thigh in all directions.", ["hip_bone", "femur", "m_gluteus", "m_iliacus", "m_psoas", "m_piriformis"]),
    ("knee", "Knee", "A hinge-like joint between the femur and the tibia with the patella in front. Ligaments hold it together and the menisci cushion it. It mainly bends and straightens the leg and allows a small rotation when bent.", ["femur", "tibia", "patella", "fibula", "m_rectus_femoris", "m_vastus", "m_popliteus"]),
    ("ankle", "Ankle and foot", "The talus links the tibia and the fibula to the foot. The ankle lifts and lowers the foot; the joints below it turn the sole in and out. The arches of the foot absorb shock and act as a spring.", ["tibia", "fibula", "foot_bones", "m_tibialis", "m_fibularis", "m_soleus"]),
    ("spine", "Spine", "Stacked vertebrae separated by intervertebral disks form a flexible column that carries the head and trunk and protects the spinal cord. The cervical, thoracic and lumbar regions have different ranges of movement; the sacrum joins the pelvis.", ["vertebrae", "disks", "ribs", "sternum"]),
]
muscles = [
    ("quads", "Quadriceps", "The rectus femoris and the three vastus muscles on the front of the thigh straighten the knee; the rectus femoris also bends the hip. They absorb impact when landing and take the load in standing up and stair climbing.", ["m_rectus_femoris", "m_vastus"]),
    ("hamstrings", "Hamstrings", "The semitendinosus and the semimembranosus at the back of the thigh bend the knee and extend the hip; they work against the quadriceps in running and in controlling the knee.", ["m_semitendinosus", "m_semimembranosus"]),
    ("gluteals", "Gluteal muscles", "The gluteus maximus is the largest muscle of the body and extends the hip (standing up, climbing); the gluteus medius and minimus hold the pelvis level when walking on one leg.", ["m_gluteus"]),
    ("calf", "Calf and shin", "The soleus and the plantaris (with the gastrocnemius, not part of the open dataset) lift the heel through the Achilles tendon; the tibialis anterior lifts the foot; the tibialis posterior and the fibularis muscles support the arch and turn the foot.", ["m_soleus", "m_plantaris", "m_tibialis", "m_fibularis", "m_extensor_digitorum_longus", "m_extensor_hallucis", "m_flexor_digitorum_longus", "m_flexor_hallucis"]),
    ("thigh_inner", "Inner thigh and hip flexors", "The gracilis, the pectineus, the sartorius and the iliopsoas (iliacus and psoas major) bend and rotate the hip and bring the thigh towards the midline.", ["m_gracilis", "m_pectineus", "m_sartorius", "m_iliacus", "m_psoas"]),
    ("cuff", "Rotator cuff and shoulder blade", "The supraspinatus, the infraspinatus, the teres minor and the subscapularis hold the head of the humerus in the socket and rotate the arm; the rhomboids and the levator scapulae position the shoulder blade.", ["m_supraspinatus", "m_infraspinatus", "m_teres", "m_subscapularis", "m_rhomboid", "m_levator_scapulae"]),
    ("chest", "Chest wall", "The pectoralis major and minor and the serratus anterior move the arm and the shoulder blade forward; the subclavius steadies the clavicle. The ribs also move with breathing.", ["m_pectoralis", "m_serratus_anterior", "m_subclavius"]),
    ("forearm", "Forearm", "Flexors on the palm side bend the wrist and the fingers; extensors on the back straighten them. The pronators and the supinator turn the forearm.", ["m_flexor_carpi", "m_flexor_digitorum", "m_flexor_pollicis", "m_extensor_carpi", "m_extensor_digit", "m_extensor_indicis", "m_extensor_pollicis", "m_pronator", "m_supinator", "m_palmaris"]),
    ("trunk", "Trunk and neck", "The external oblique and the other abdominal muscles flex and rotate the trunk and raise pressure in the abdomen; the sternocleidomastoid, the scalenes and the splenius muscles turn and tilt the head and neck.", ["m_external_oblique", "m_sternocleidomastoid", "m_scalenus", "m_splenius", "m_iliocostalis", "m_longissimus", "m_spinalis", "m_platysma"]),
]
tabs["biomech"] = {
    "model": "body", "title": "BioMech",
    "intro": "A 3D map of the bones, muscles and joints that move the body. Pick a joint to see the bones that meet there and the muscles that move them, or a muscle group to see where it sits. The model shows the muscles the open dataset provides; some muscles (for example the deltoid and the biceps) are not part of it.",
    "layers": layers(["skeletal", "muscular"]),
    "hidden": [S(s) for s in ALL_SYS if s not in ("skeletal", "muscular")],
    "groups": [
        {"id": "joints", "label": "Joints", "topics": [T("j_" + i, t, x, h) for i, t, x, h in joints]},
        {"id": "muscles", "label": "Muscle groups", "topics": [T("mg_" + i, t, x, h) for i, t, x, h in muscles]},
        {"id": "principles", "label": "Principles", "topics": [
            T("levers", "Levers", "Bones act as levers, joints as pivots and muscles as the force. Most limbs use lever arrangements that favour speed and range over force, which is why muscles must pull much harder than the load they move.", [S("skeletal"), S("muscular")]),
            T("tendons", "Tendons and ligaments", "Tendons join muscle to bone and store and return elastic energy (the Achilles tendon is the best-known example); ligaments join bone to bone and limit movement. Both are rich in collagen and adapt slowly to load.", ["femur", "tibia", "foot_bones"]),
            T("gait", "Walking", "Each step moves through a stance phase, in which the foot carries the weight, and a swing phase. The hip extensors, the quadriceps, the calf muscles and the foot dorsiflexors act in turn, while the gluteus medius keeps the pelvis level.", ["m_gluteus", "m_rectus_femoris", "m_vastus", "m_soleus", "m_tibialis", "femur", "tibia"]),
        ]},
    ],
}

# ------------------------------------------------------------------------------------------------------------- Genetic
tabs["genetic"] = {
    "model": "dna", "title": "Genetic",
    "intro": "A schematic 3D model of the DNA double helix (30 base pairs, about three turns). Orbit it, zoom in, tap a part to name it, or pick a part below to light it.",
    "layers": [{"id": "backbone", "label": "Backbones", "match": ["backbone_"]}, {"id": "bases", "label": "Bases", "match": ["base_"]}],
    "hidden": [],
    "groups": [
        {"id": "structure", "label": "Structure", "topics": [
            T("helix", "The double helix", "DNA is two long strands wound around each other in a right-handed spiral. One full turn of the common B form is about ten base pairs, about 3.4 nanometres long, and the helix is about 2 nanometres wide.", ["backbone_", "base_"]),
            T("backbone", "Sugar-phosphate backbone", "Each strand has a backbone of alternating deoxyribose sugars and phosphate groups. The two backbones run in opposite directions (antiparallel); the model shows them in blue and violet.", ["backbone_"], True),
            T("pairing", "Base pairing", "The bases in the middle hold the strands together with hydrogen bonds: adenine always pairs with thymine (two bonds) and guanine with cytosine (three bonds). This fixed pairing is what lets one strand serve as the template for the other.", ["base_"], True),
        ]},
        {"id": "bases", "label": "The four bases", "topics": [
            T("adenine", "Adenine (A)", "A purine, a double-ring base. It pairs with thymine.", ["base_A"], True),
            T("thymine", "Thymine (T)", "A pyrimidine, a single-ring base. It pairs with adenine. In RNA, uracil takes its place.", ["base_T"], True),
            T("guanine", "Guanine (G)", "A purine, a double-ring base. It pairs with cytosine through three hydrogen bonds.", ["base_G"], True),
            T("cytosine", "Cytosine (C)", "A pyrimidine, a single-ring base. It pairs with guanine.", ["base_C"], True),
        ]},
        {"id": "function", "label": "From DNA to protein", "topics": [
            T("replication", "Replication", "Before a cell divides, enzymes unwind the helix and each strand is copied by base pairing, so that each new double helix holds one old and one new strand. Proofreading enzymes keep the error rate very low.", ["backbone_", "base_"]),
            T("dogma", "Transcription and translation", "A gene is read by copying one strand into messenger RNA (transcription). Ribosomes then read the message three bases at a time, each triplet (codon) choosing one amino acid, and chain the amino acids into a protein (translation).", ["base_"]),
            T("genome", "Genes and chromosomes", "In human cells DNA is wound around proteins and packed into 23 pairs of chromosomes. The human genome holds about 3 billion base pairs and roughly 20,000 protein-coding genes; most of the sequence regulates genes or has other roles.", ["backbone_"]),
            T("variation", "Mutation and variation", "Changes in the sequence arise from copying errors and damage. Most have no effect, some change a protein, and a few cause or influence disease. Differences between people are mostly single base changes (variants) scattered through the genome.", ["base_G", "base_C"]),
        ]},
    ],
}

# --------------------------------------------------------------------------------------------------------------- Neuro
tabs["neuro"] = {
    "model": "brain", "title": "Neuro",
    "intro": "A 3D brain with its macro regions. Pick a region to light it, or tap the model to name the part under the finger. Hiding the cortex lobes (Layers) shows the deep structures.",
    "layers": [{"id": "lobes", "label": "Cortex lobes", "match": ["frontal_lobe", "parietal_lobe", "temporal_lobe", "occipital_lobe", "limbic_lobe", "cerebrum_other"]},
               {"id": "deep", "label": "Deep structures", "match": ["hippocampus", "amygdala", "thalamus", "hypothalamus", "basal_ganglia", "corpus_callosum", "insula", "ventricles"]},
               {"id": "stem", "label": "Brainstem and cerebellum", "match": ["cerebellum", "midbrain", "pons", "medulla"]}],
    "hidden": [],
    "groups": [
        {"id": "cortex", "label": "Cortex", "topics": [
            T("frontal", "Frontal lobe", "The largest lobe, at the front. It plans and decides, holds information in working memory, controls voluntary movement through the motor strip and, in most people, contains the area that produces speech on the left side.", ["frontal_lobe"]),
            T("parietal", "Parietal lobe", "Above and behind the frontal lobe. It processes touch and body position, builds a map of the space around the body and combines the senses to guide movement and attention.", ["parietal_lobe"]),
            T("temporal", "Temporal lobe", "On the side, above the ear. It processes sound, takes part in recognising faces and objects, and the deep part forms and recalls memories; in most people the left side supports understanding of language.", ["temporal_lobe"]),
            T("occipital", "Occipital lobe", "At the back. It receives the signals of the eyes and builds the first stages of vision: edges, motion, colour and shape.", ["occipital_lobe"]),
            T("limbic", "Limbic lobe", "A ring of cortex on the inner face of each hemisphere, including the cingulate gyrus. It links emotion, memory and the control of bodily states.", ["limbic_lobe"]),
            T("insula", "Insula", "Folded deep inside the lateral groove. It takes part in sensing the state of the body (interoception), taste, pain and emotional awareness.", ["insula"]),
        ]},
        {"id": "deep", "label": "Deep structures", "topics": [
            T("thalamus", "Thalamus", "A relay and filter: nearly all sensory information (except smell) passes through the thalamus on its way to the cortex, and it takes part in attention and wakefulness.", ["thalamus"], True),
            T("hypothalamus", "Hypothalamus", "A small region that holds the body in balance: temperature, hunger and thirst, sleep and the circadian clock, and the hormone control of the pituitary gland.", ["hypothalamus"], True),
            T("hippocampus", "Hippocampus", "A curved structure of the temporal lobe that is essential for forming new memories of facts and events and for spatial navigation.", ["hippocampus"], True),
            T("amygdala", "Amygdala", "An almond-shaped group of nuclei that tags experiences with emotional importance, especially fear and threat, and prepares the body to react.", ["amygdala"], True),
            T("basal", "Basal ganglia", "The caudate nucleus, the putamen and the globus pallidus. They help select and start movements and take part in habit learning and reward.", ["basal_ganglia"], True),
            T("callosum", "Corpus callosum", "The great bundle of about 200 million fibres that connects the two hemispheres so that they work together.", ["corpus_callosum"], True),
            T("ventricles", "Ventricles", "Fluid-filled cavities inside the brain. They make and circulate cerebrospinal fluid, which cushions the brain, carries nutrients and clears waste.", ["ventricles"], True),
        ]},
        {"id": "stem", "label": "Brainstem and cerebellum", "topics": [
            T("cerebellum", "Cerebellum", "The 'little brain' at the back and below. It coordinates movement, balance and timing and learns skilled movements; it holds a large share of all the neurons of the brain.", ["cerebellum"]),
            T("midbrain", "Midbrain", "The top of the brainstem. It takes part in eye movements, reflexes to sight and sound, and the control of movement through dopamine-producing cells.", ["midbrain"], True),
            T("pons", "Pons", "A bridge of fibres between the brain, the cerebellum and the spinal cord. It takes part in sleep, breathing control and facial sensation and movement.", ["pons"], True),
            T("medulla", "Medulla oblongata", "The lowest part of the brainstem, continuous with the spinal cord. It runs automatic functions such as heart rate, blood pressure, breathing and swallowing, and most motor fibres cross here.", ["medulla"], True),
        ]},
    ],
}

# ------------------------------------------------------------------------------------------------------------- Immuno
tabs["immuno"] = {
    "model": "body", "title": "Immuno",
    "intro": "The immune system and the main groups of immune-related disease. Organs of the system are lit on the body where the model has them; lymph nodes, bone marrow and the mucosal tissues are described but not drawn.",
    "layers": layers(["skeletal", "muscular", "integumentary"]),
    "hidden": [S("muscular"), S("integumentary")],
    "groups": [
        {"id": "organs", "label": "Organs and tissues", "topics": [
            T("thymus", "Thymus", "A gland behind the breastbone where T cells mature and learn to tell the body's own cells from foreign ones. It is largest in childhood and shrinks with age.", ["thymus"], True),
            T("spleen", "Spleen", "The largest filter of the blood: its red pulp removes old red cells and microbes and its white pulp is a site where immune responses start.", ["spleen"], True),
            T("marrow", "Bone marrow", "The site where all blood cells, including white blood cells, are made. In adults red marrow lies in the flat bones and the ends of long bones (breastbone, ribs, vertebrae, pelvis, femur). It is not drawn separately in the model; the bones that hold it are lit.", ["sternum", "ribs", "vertebrae", "hip_bone", "femur"]),
            T("nodes", "Lymph nodes and vessels", "A network of vessels returns tissue fluid (lymph) to the blood through lymph nodes, small filters packed with immune cells that meet microbes and antigens there. Swollen nodes are a common sign of an immune response. Lymph nodes are not part of the model.", ["thymus", "spleen"]),
            T("mucosa", "Barriers: skin and mucosa", "The skin, the lining of the airway and the lining of the gut are the first barrier. They keep microbes out with tight cell layers, mucus, acid, enzymes and resident microbes, and the gut holds a large share of the body's immune cells.", [S("integumentary"), "trachea", "small_intestine", "large_intestine", "stomach"]),
        ]},
        {"id": "response", "label": "How it responds", "topics": [
            T("innate", "Innate immunity", "The fast, general response. Neutrophils and macrophages engulf microbes, natural killer cells attack infected or abnormal cells, and the complement proteins mark microbes for removal. Inflammation (redness, heat, swelling, pain) brings more cells and fluid to the site.", ["spleen", "thymus"]),
            T("adaptive", "Adaptive immunity", "The slower, specific response with memory. B cells make antibodies against a particular antigen; T cells help other cells (helper T cells) or kill infected cells (cytotoxic T cells). After the first meeting, memory cells allow a faster and stronger response.", ["thymus", "spleen", "sternum", "femur"]),
            T("antibodies", "Antibodies", "Y-shaped proteins made by B cells that bind one antigen. They neutralise microbes and toxins, mark them for removal by other cells and trigger complement. The main classes are IgG, IgM, IgA, IgE and IgD.", ["spleen"]),
            T("vaccines", "Vaccination", "A vaccine shows the immune system a harmless form or piece of a microbe so that it builds memory cells before a real infection. Herd protection arises when enough people in a community are immune.", ["thymus", "spleen"]),
            T("tolerance", "Tolerance and regulation", "Immune cells that react strongly to the body's own molecules are removed or silenced in the thymus and marrow, and regulatory T cells hold the response in check. Failures of these controls underlie autoimmune disease.", ["thymus"]),
        ]},
        {"id": "pathology", "label": "Main pathology groups", "topics": [
            T("infection", "Infection", "Disease caused by bacteria, viruses, fungi or parasites. Outcome depends on the microbe and on the host: the response clears most infections, but the same inflammation can also cause damage (as in sepsis, where the response is widespread and dysregulated).", ["lungs", "trachea", "small_intestine", "large_intestine", "kidneys", "urinary_bladder"]),
            T("allergy", "Allergy and hypersensitivity", "An exaggerated response to a harmless substance. In immediate allergy IgE antibodies trigger mast cells to release histamine within minutes (hay fever, food allergy, anaphylaxis); other types are delayed and driven by T cells (contact dermatitis).", ["lungs", "trachea", "small_intestine", S("integumentary")]),
            T("autoimmune", "Autoimmune disease", "The immune system attacks the body's own tissue. Examples include type 1 diabetes (pancreatic islet cells), rheumatoid arthritis (joints), multiple sclerosis (nervous system), Hashimoto thyroiditis and coeliac disease (small intestine).", ["pancreas", "small_intestine", "brain", "spinal_cord", "femur", "tibia", "humerus"]),
            T("immunodef", "Immunodeficiency", "A part of the defence is missing or weak: inherited (for example severe combined immunodeficiency), acquired through infection (HIV attacks helper T cells) or caused by treatment such as chemotherapy or immunosuppressive drugs. People are then prone to unusual or severe infections.", ["thymus", "spleen", "sternum", "femur", "vertebrae"]),
            T("cancer", "Cancer and immune surveillance", "Cells with changed proteins can be recognised and killed by T and natural killer cells, but tumours may escape by hiding or by switching immune cells off. Immunotherapy releases these brakes or adds engineered cells.", ["lungs", "liver", "stomach", "large_intestine", "pancreas"]),
            T("inflammation", "Chronic inflammation", "When inflammation does not resolve it damages tissue and contributes to disease: atherosclerosis of the arteries, inflammatory bowel disease, chronic lung disease and joint disease. It is also tied to obesity, sleep loss and ageing.", ["arteries", "aorta", "heart", "large_intestine", "small_intestine", "lungs"]),
            T("cardio_path", "Cardiovascular disease", "Atherosclerosis builds plaques of lipids and immune cells in artery walls; a blocked coronary artery causes a heart attack and a blocked brain artery a stroke. High blood pressure, high cholesterol, diabetes and smoking are main risk factors.", ["heart", "aorta", "arteries", "brain"]),
            T("metabolic_path", "Metabolic disease", "Diabetes mellitus is a failure to control blood glucose, because of too little insulin (type 1) or reduced response to it (type 2); the liver, the pancreas, the kidneys, the eyes, the nerves and the vessels are affected over time.", ["pancreas", "liver", "kidneys", "eyes", "arteries"]),
            T("neuro_path", "Neurodegenerative disease", "Conditions in which nerve cells are lost gradually, such as Alzheimer disease, Parkinson disease and motor neuron disease. They involve abnormal protein build-up and, increasingly recognised, inflammation of the nervous system.", ["brain", "spinal_cord"]),
        ]},
    ],
}


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 16), b""): h.update(b)
    return h.hexdigest()


def main(d):
    models = {}
    info = {"body": ("body.glb", "CC BY-SA 2.1 JP", BP3D), "brain": ("brain.glb", "CC BY-SA 2.1 JP", BP3D),
            "dna": ("dna.glb", "CC0 1.0", "Generated by this project's tools/build_models.py (a schematic B-DNA helix); dedicated to the public domain.")}
    for k, (f, lic, credit) in info.items():
        p = os.path.join(d, f)
        models[k] = {"file": f, "sha256": sha(p), "size": os.path.getsize(p), "licence": lic, "credit": credit}
    doc = {"schemaVersion": 1, "notice": NOTICE, "models": models, "tabs": tabs}
    json.dump(doc, open(os.path.join(d, "content.json"), "w"), indent=1, ensure_ascii=False); open(os.path.join(d, "content.json"), "a").write("\n")
    reg = {
        "schemaVersion": 1,
        "_doc": "The licence register of every file served from data/fleet/biomed (#1777). Each file: its licence (an SPDX-style id), the source it comes from, the attribution it requires and its sha256. test-assets-licensed.sh holds the directory to this register.",
        "files": {
            "body.glb": {"licence": "CC-BY-SA-2.1-JP", "source": "https://dbarchive.biosciencedbc.jp/data/bodyparts3d/LATEST/ (BodyParts3D 4.0, isa_BP3D_4.0_obj_99.zip)", "author": "The Database Center for Life Science (DBCLS)", "derived": "tools/build_models.py (vertex-clustering decimation, regrouped into named nodes); shared under the same licence", "sha256": models["body"]["sha256"]},
            "brain.glb": {"licence": "CC-BY-SA-2.1-JP", "source": "https://dbarchive.biosciencedbc.jp/data/bodyparts3d/LATEST/ (BodyParts3D 4.0, isa_BP3D_4.0_obj_99.zip)", "author": "The Database Center for Life Science (DBCLS)", "derived": "tools/build_models.py (brain parts only); shared under the same licence", "sha256": models["brain"]["sha256"]},
            "dna.glb": {"licence": "CC0-1.0", "source": "tools/build_models.py (generated, no third-party input)", "author": "Hardtech-BioMed", "derived": "", "sha256": models["dna"]["sha256"]},
            "content.json": {"licence": "CC0-1.0", "source": "tools/build_content.py (original educational text, no third-party input)", "author": "Hardtech-BioMed", "derived": "", "sha256": sha(os.path.join(d, "content.json"))},
        },
    }
    json.dump(reg, open(os.path.join(d, "assets.json"), "w"), indent=1); open(os.path.join(d, "assets.json"), "a").write("\n")
    print(json.dumps({k: v["size"] for k, v in models.items()}))


if __name__ == "__main__":
    main(sys.argv[1])
